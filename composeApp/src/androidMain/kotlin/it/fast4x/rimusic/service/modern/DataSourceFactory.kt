package it.fast4x.rimusic.service.modern

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.R
import it.fast4x.rimusic.utils.asSong
import it.fast4x.rimusic.utils.buildPlaybackOkHttpClient
import it.fast4x.rimusic.extensions.players.SelectSimplePlayerType
import it.fast4x.rimusic.models.Format
import it.fast4x.rimusic.service.isLocal
import it.fast4x.rimusic.utils.InvalidHttpCodeException
import it.fast4x.rimusic.utils.findCause
import it.fast4x.rimusic.utils.handleRangeErrors
import it.fast4x.rimusic.utils.retryIf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException


private const val STREAM_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

private data class CachedUrl(
    val url: String,
    val contentLength: Long?,
    val expiresAt: Long,
)

@OptIn(UnstableApi::class)
internal fun PlayerServiceModern.createSimpleDataSourceFactory(scope: CoroutineScope): DataSource.Factory {
    val songUrlCache = HashMap<String, CachedUrl>()

    val upstreamFactory = DefaultDataSource.Factory(
        this,
        OkHttpDataSource.Factory(buildPlaybackOkHttpClient())
            .setUserAgent(STREAM_UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://music.youtube.com/",
                    "Origin" to "https://music.youtube.com",
                )
            )
    )

    // Cache chain: downloadCache (read-only, user-downloaded songs) → principalCache (read-write, streaming buffer) → network.
    val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null) // never write into download cache during playback
                .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
        )
        .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    return ResolvingDataSource.Factory(cacheFactory) resolver@{ dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        // If the URI is already an http(s) URL, this open call is for a
        // previously-resolved network URI being passed through the chain
        // (e.g. internal redirect/cache miss handling). Leave it untouched.
        val uriStr = dataSpec.uri.toString()
        if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
            return@resolver dataSpec
        }

        if (dataSpec.isLocal) return@resolver dataSpec

        // Ensure current song is in DB
        val mediaItem = runBlocking {
            withContext(Dispatchers.Main) { player.currentMediaItem }
        }
        Database.asyncTransaction {
            if (mediaItem != null) insert(mediaItem.asSong)
        }

        // If the entire requested range is fully present in EITHER cache
        // (download cache or streaming principal cache), pass the dataSpec
        // through unchanged — CacheDataSource will serve entirely from cache
        // and never open the upstream network source.
        val requestedLength = if (dataSpec.length >= 0) dataSpec.length else Long.MAX_VALUE
        val isFullyDownloaded = try {
            downloadCache.isCached(mediaId, dataSpec.position, requestedLength)
        } catch (e: Exception) {
            println("ModernDSF: downloadCache.isCached failed for $mediaId: ${e.message}")
            false
        }
        val isFullyCached = try {
            cache.isCached(mediaId, dataSpec.position, requestedLength)
        } catch (e: Exception) {
            println("ModernDSF: cache.isCached failed for $mediaId: ${e.message}")
            false
        }
        if (isFullyDownloaded || isFullyCached) {
            println("ModernDSF: $mediaId fully cached/downloaded at pos=${dataSpec.position} (downloaded=$isFullyDownloaded, cached=$isFullyCached)")
            return@resolver dataSpec
        }

        // Resolve the signed CDN URL (from in-memory cache or fresh InnerTube call).
        // We ALWAYS need the real network URL when there's any possibility of going
        // to the network — even if some bytes are cached, CacheDataSource will open
        // the upstream DataSource for the uncached portion and needs a valid URL.
        var streamUrl: String? = null

        val cached = songUrlCache[mediaId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            streamUrl = cached.url
        }

        if (streamUrl == null) {
            val playedFormat = runBlocking(Dispatchers.IO) { Database.format(mediaId).first() }
            val playbackData = runBlocking(Dispatchers.IO) {
                SelectSimplePlayerType(mediaId, playedFormat, audioQualityFormat)
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable
                    is ConnectException, is UnknownHostException ->
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    is SocketTimeoutException ->
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        )
                    else ->
                        throw PlaybackException(
                            getString(R.string.error_unknown),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                }
            }

            val format = playbackData.format
            streamUrl = playbackData.streamUrl

            Database.asyncTransaction {
                if (songExist(mediaId) > 0) upsert(
                    Format(
                        songId = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        bitrate = format.bitrate.toLong(),
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                    )
                )
            }

            val host = Uri.parse(streamUrl).host ?: ""
            println("ModernDSF resolved $mediaId itag=${format.itag} host=$host clen=${format.contentLength} pos=${dataSpec.position} len=${dataSpec.length}")
            songUrlCache[mediaId] = CachedUrl(
                url = streamUrl,
                contentLength = format.contentLength,
                expiresAt = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
            )
        }

        // Replace the placeholder URI (video id) with the signed CDN URL.
        // Cache key stays as the mediaId (set via setCustomCacheKey when building
        // the MediaItem), so caching continues to work across URL refreshes.
        dataSpec.buildUpon()
            .setUri(streamUrl.toUri())
            .build()
    }
        .retryIf(
            maxRetries = 3,
            printStackTrace = true,
            exponential = false,
        ) { ex ->
            val code = ex.findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()?.responseCode
                ?: ex.findCause<InvalidHttpCodeException>()?.code
            if (code == 403 || code == 416) {
                // Drop cached signed URL so the resolver re-fetches a fresh one on retry.
                synchronized(songUrlCache) { songUrlCache.clear() }
                println("ModernDSF: got HTTP $code, clearing cached URL and retrying with a fresh one")
            }
            code == 403 || code == 416
        }
        .handleRangeErrors()
}
