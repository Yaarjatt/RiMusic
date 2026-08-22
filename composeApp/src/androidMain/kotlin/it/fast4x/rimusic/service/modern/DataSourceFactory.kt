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
private const val STREAM_REFERER = "https://www.youtube.com/"
private const val STREAM_ORIGIN = "https://www.youtube.com"

private data class CachedUrl(
    val url: String,
    val contentLength: Long?,
    val expiresAt: Long,
)

@OptIn(UnstableApi::class)
internal fun PlayerServiceModern.createSimpleDataSourceFactory(scope: CoroutineScope): DataSource.Factory {
    val songUrlCache = HashMap<String, CachedUrl>()

    // Single network upstream (OkHttp + our Range/UA interceptor) wrapped in DefaultDataSource
    // for non-http URIs (file://, content://, asset://, etc.).
    val httpUpstreamFactory = OkHttpDataSource.Factory(buildPlaybackOkHttpClient())
        .setUserAgent(STREAM_UA)
        .setDefaultRequestProperties(
            mapOf(
                "Referer" to STREAM_REFERER,
                "Origin" to STREAM_ORIGIN,
            )
        )
    val upstreamFactory = DefaultDataSource.Factory(this, httpUpstreamFactory)

    // Simple single-tier playback cache: principalCache in front of network.
    // Downloads are handled separately by MyDownloadHelper; online playback uses one cache.
    val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    return ResolvingDataSource.Factory(cacheFactory) resolver@{ dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        // Pass through local files unchanged.
        if (dataSpec.isLocal) return@resolver dataSpec

        // If URI is already an http(s) URL, this open is CacheDataSource re-opening the
        // upstream for an already-resolved network URI — pass through unchanged.
        val uriStr = dataSpec.uri.toString()
        if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
            return@resolver dataSpec
        }

        // Ensure current song is in DB
        val mediaItem = runBlocking {
            withContext(Dispatchers.Main) { player.currentMediaItem }
        }
        Database.asyncTransaction {
            if (mediaItem != null) insert(mediaItem.asSong)
        }

        // Resolve the signed CDN URL (from in-memory cache or fresh InnerTube call).
        var streamUrl: String? = null
        var contentLength: Long? = null

        val cached = songUrlCache[mediaId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            streamUrl = cached.url
            contentLength = cached.contentLength
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
            contentLength = format.contentLength

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
            println("ModernDSF resolved $mediaId itag=${format.itag} host=$host clen=$contentLength pos=${dataSpec.position} len=${dataSpec.length}")
            songUrlCache[mediaId] = CachedUrl(
                url = streamUrl,
                contentLength = contentLength,
                expiresAt = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
            )
        }

        // Replace the placeholder media-id URI with the signed CDN URL.
        // Do NOT call .subrange() or cap DataSpec.length — ExoPlayer manages chunking.
        // The OkHttp interceptor will bound any missing/open-ended Range headers to 2 MB.
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
                // Drop all cached signed URLs so the resolver re-fetches fresh ones on retry.
                synchronized(songUrlCache) { songUrlCache.clear() }
                println("ModernDSF: got HTTP $code, clearing cached URL and retrying with a fresh one")
            }
            code == 403 || code == 416
        }
        .handleRangeErrors()
}
