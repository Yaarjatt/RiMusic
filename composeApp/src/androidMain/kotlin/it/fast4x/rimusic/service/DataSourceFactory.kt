package it.fast4x.rimusic.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.R
import it.fast4x.rimusic.appContext
import it.fast4x.rimusic.enums.AudioQualityFormat
import it.fast4x.rimusic.extensions.players.SelectSimplePlayerType
import it.fast4x.rimusic.models.Format
import it.fast4x.rimusic.service.MyDownloadHelper.downloadCache
import it.fast4x.rimusic.utils.InvalidHttpCodeException
import it.fast4x.rimusic.utils.buildPlaybackOkHttpClient
import it.fast4x.rimusic.utils.findCause
import it.fast4x.rimusic.utils.handleRangeErrors
import it.fast4x.rimusic.utils.principalCache
import it.fast4x.rimusic.utils.retryIf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
private fun buildPlaybackUpstreamFactory(context: Context) =
    DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(buildPlaybackOkHttpClient())
            .setUserAgent(STREAM_UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to STREAM_REFERER,
                    "Origin" to STREAM_ORIGIN,
                )
            )
    )

/**
 * Build a ResolvingDataSource resolver that maps video-id DataSpecs to signed
 * googlevideo URLs, handling in-memory URL caching, content-length tracking,
 * database upserts, and graceful "fully cached" short-circuiting.
 */
@OptIn(UnstableApi::class)
private fun makeResolver(
    cache: Cache,
    audioQualityFormat: AudioQualityFormat,
    tag: String,
    allowFullyCachedShortcut: Boolean,
): (DataSpec) -> DataSpec {
    val songUrlCache = HashMap<String, CachedUrl>()

    return resolver@{ dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        if (dataSpec.isLocal) return@resolver dataSpec

        val uriStr = dataSpec.uri.toString()
        if (uriStr.startsWith("http://") || uriStr.startsWith("https://"))
            return@resolver dataSpec

        // If the entire requested range is already fully present in the cache,
        // pass through — CacheDataSource will serve it without hitting the network.
        if (allowFullyCachedShortcut) {
            val requestedLength = if (dataSpec.length >= 0) dataSpec.length else Long.MAX_VALUE
            val fullyCached = try {
                cache.isCached(mediaId, dataSpec.position, requestedLength)
            } catch (e: Exception) {
                println("$tag: isCached failed for $mediaId: ${e.message}")
                false
            }
            if (fullyCached) {
                println("$tag: $mediaId fully cached at pos=${dataSpec.position}")
                return@resolver dataSpec
            }
        }

        // Resolve signed CDN URL (memory cache → InnerTube API).
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
                            "No internet",
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    is SocketTimeoutException ->
                        throw PlaybackException(
                            "Timeout",
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        )
                    else ->
                        throw PlaybackException(
                            "Unknown error",
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
            println("$tag resolved $mediaId itag=${format.itag} host=$host clen=$contentLength pos=${dataSpec.position} len=${dataSpec.length}")
            songUrlCache[mediaId] = CachedUrl(
                url = streamUrl,
                contentLength = contentLength,
                expiresAt = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
            )
        }

        dataSpec.buildUpon()
            .setUri(streamUrl.toUri())
            .build()
    }
}

@OptIn(UnstableApi::class)
private fun wrapFactory(
    baseFactory: DataSource.Factory,
    resolver: (DataSpec) -> DataSpec,
    urlCache: HashMap<String, CachedUrl>,
): DataSource.Factory =
    ResolvingDataSource.Factory(baseFactory, resolver)
        .retryIf(maxRetries = 3, printStackTrace = true, exponential = false) { ex ->
            val code = ex.findCause<InvalidResponseCodeException>()?.responseCode
                ?: ex.findCause<InvalidHttpCodeException>()?.code
            if (code == 403 || code == 416) {
                synchronized(urlCache) { urlCache.clear() }
                println("DSF retry: got HTTP $code, clearing cached URL for fresh fetch")
            }
            code == 403 || code == 416
        }
        .handleRangeErrors()


/* ============ MyDownloadHelper ============ */

@OptIn(UnstableApi::class)
internal fun MyDownloadHelper.createSimpleDataSourceFactory(): DataSource.Factory {
    val urlCache = HashMap<String, CachedUrl>()
    val cache = getDownloadCache(appContext())
    val base = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(buildPlaybackUpstreamFactory(appContext()))
    val resolver = makeResolver(cache, audioQualityFormat, "DownloadDSF", allowFullyCachedShortcut = false)
    return wrapFactory(base, resolver, urlCache)
}


/* ============ MyPreCacheHelper ============ */

@OptIn(UnstableApi::class)
internal fun MyPreCacheHelper.createSimpleDataSourceFactory(): DataSource.Factory {
    val urlCache = HashMap<String, CachedUrl>()
    val cache = principalCache.getInstance(appContext())
    val base = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(buildPlaybackUpstreamFactory(appContext()))
    val resolver = makeResolver(cache, audioQualityFormat, "PreCacheDSF", allowFullyCachedShortcut = false)
    return wrapFactory(base, resolver, urlCache)
}


/* ============ PlayerService (legacy) ============ */

@OptIn(UnstableApi::class)
internal fun PlayerService.createSimpleDataSourceFactory(): DataSource.Factory {
    val urlCache = HashMap<String, CachedUrl>()
    val cache = principalCache.getInstance(appContext())
    val base = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(buildPlaybackUpstreamFactory(appContext()))
    val resolver = makeResolver(cache, audioQualityFormat, "PlayerDSF", allowFullyCachedShortcut = true)
    return wrapFactory(base, resolver, urlCache)
}
