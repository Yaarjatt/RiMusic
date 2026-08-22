package it.fast4x.rimusic.utils

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.fast4x.environment.utils.ProxyPreferences
import it.fast4x.environment.utils.getProxy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


/**
 * Bounded byte-range size used when ExoPlayer/OkHttpDataSource opens a connection to googlevideo
 * without an explicit Range, or with an open-ended Range (which CDN rejects with 403). 2 MiB.
 */
private const val STREAM_RANGE_CHUNK = 2L * 1024L * 1024L

private const val STREAM_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
private const val STREAM_REFERER = "https://www.youtube.com/"
private const val STREAM_ORIGIN = "https://www.youtube.com"

@UnstableApi
class RangeHandlerDataSourceFactory(private val parent: DataSource.Factory) : DataSource.Factory {
    class Source(private val parent: DataSource) : DataSource by parent {
        @OptIn(UnstableApi::class)
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { e ->
            if (e.cause is InvalidResponseCodeException && (e.cause as InvalidResponseCodeException).responseCode == 416) parent.open(
                dataSpec
                    .withRequestHeaders(
                        dataSpec.httpRequestHeaders.filter {
                            it.key.equals("range", ignoreCase = true)
                        }
                    )
            )
            else throw e
        }
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

@UnstableApi
class CatchingDataSourceFactory(private val parent: DataSource.Factory) : DataSource.Factory {
    class Source(private val parent: DataSource) : DataSource by parent {
        @OptIn(UnstableApi::class)
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse {
            it.printStackTrace()

            if (it is PlaybackException) throw it
            else throw PlaybackException(
                "Unknown playback error",
                it,
                PlaybackException.ERROR_CODE_UNSPECIFIED
            )
        }
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

@OptIn(UnstableApi::class)
fun DataSource.Factory.handleRangeErrors(): DataSource.Factory = RangeHandlerDataSourceFactory(this)

@OptIn(UnstableApi::class)
fun DataSource.Factory.handleCatchingErrors(): DataSource.Factory = CatchingDataSourceFactory(this)

val Context.defaultDataSourceFactory
    @OptIn(UnstableApi::class)
    get() = DefaultDataSource.Factory(
        this,
        DefaultHttpDataSource.Factory().setConnectTimeoutMs(16000)
            .setReadTimeoutMs(8000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
    )

val Context.okHttpDataSourceFactory
    @OptIn(UnstableApi::class)
    get() = DefaultDataSource.Factory(
        this,
        OkHttpDataSource
            .Factory(buildPlaybackOkHttpClient())
            .setUserAgent(STREAM_UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to STREAM_REFERER,
                    "Origin" to STREAM_ORIGIN,
                )
            )
    )

/**
 * Interceptor that forces every googlevideo.com request to:
 *  1. Use GET (never HEAD — CDN returns 403 for HEAD).
 *  2. Carry a Safari-on-macOS User-Agent + youtube.com Referer/Origin if missing.
 *  3. Carry a BOUNDED Range header:
 *       - If no Range is present: "bytes=0-<STREAM_RANGE_CHUNK-1>"
 *       - If Range is open-ended "bytes=N-": "bytes=N-<N+STREAM_RANGE_CHUNK-1>"
 *       - If Range is already bounded (bytes=N-M): leave untouched.
 *     This is required because googlevideo.com rejects open-ended and missing Range requests
 *     with HTTP 403 for most videos.
 */
@UnstableApi
private class GoogleVideoRangeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!request.url.host.endsWith(".googlevideo.com")) {
            return chain.proceed(request)
        }

        val b = request.newBuilder()

        // HEAD → GET
        if (request.method == "HEAD") {
            b.get()
        }

        // UA / Referer / Origin defaults
        if (request.header("User-Agent") == null) b.header("User-Agent", STREAM_UA)
        if (request.header("Referer") == null) b.header("Referer", STREAM_REFERER)
        if (request.header("Origin") == null) b.header("Origin", STREAM_ORIGIN)

        // Fix up Range header.
        val range = request.header("Range")
        when {
            range.isNullOrBlank() -> {
                // No Range at all — bound from 0.
                b.header("Range", "bytes=0-${STREAM_RANGE_CHUNK - 1}")
                b.removeHeader("Accept-Encoding") // avoid gzip on byte ranges
            }
            range.startsWith("bytes=", ignoreCase = true) -> {
                val spec = range.removePrefix("bytes=").trim()
                val dashIdx = spec.indexOf('-')
                if (dashIdx < 0) {
                    // Malformed — fallback.
                    b.header("Range", "bytes=0-${STREAM_RANGE_CHUNK - 1}")
                } else {
                    val start = spec.substring(0, dashIdx).trim()
                    val end = spec.substring(dashIdx + 1).trim()
                    val startVal = start.toLongOrNull()
                    val endVal = end.toLongOrNull()
                    when {
                        // bytes=N-  (open-ended) → bound
                        startVal != null && end.isEmpty() -> {
                            b.header("Range", "bytes=$startVal-${startVal + STREAM_RANGE_CHUNK - 1}")
                        }
                        // bytes=-N  (suffix) → leave
                        startVal == null && endVal != null -> { /* keep as-is */ }
                        // bytes=N-M (bounded) → keep
                        startVal != null && endVal != null -> { /* keep as-is */ }
                        // bytes=0-  or anything weird
                        else -> b.header("Range", "bytes=0-${STREAM_RANGE_CHUNK - 1}")
                    }
                }
            }
        }

        request = b.build()
        return chain.proceed(request)
    }
}

/**
 * Build the OkHttpClient used for playback against googlevideo.
 */
@UnstableApi
fun buildPlaybackOkHttpClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no total-call timeout for streaming
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        // Application interceptor runs once before cache/redirects — enforces bounded Range,
        // proper UA/Referer/Origin, and HEAD→GET.
        .addInterceptor(GoogleVideoRangeInterceptor())
    ProxyPreferences.preference?.let { builder.proxy(getProxy(it)) }
    return builder.build()
}


@UnstableApi
class RetryingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val maxRetries: Int,
    private val printStackTrace: Boolean,
    private val exponential: Boolean,
    private val predicate: (Throwable) -> Boolean
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        @OptIn(UnstableApi::class)
        override fun open(dataSpec: DataSpec): Long {
            var lastException: Throwable? = null
            var retries = 0
            while (retries < maxRetries) {
                if (retries > 0) Timber.d("RetryingDataSourceFactory Retry $retries of $maxRetries fetching datasource")

                @Suppress("TooGenericExceptionCaught")
                return try {
                    parent.open(dataSpec)
                } catch (ex: Throwable) {
                    lastException = ex
                    if (printStackTrace) Timber.e(
                        " RetryingDataSourceFactory Exception caught by retry mechanism",
                        ex
                    )
                    if (predicate(ex)) {
                        val time = if (exponential) 1000L * 2.0.pow(retries).toLong() else 2500L
                        Timber.d("RetryingDataSourceFactory Retry policy accepted retry, sleeping for $time milliseconds")
                        Thread.sleep(time)
                        retries++
                        continue
                    }
                    Timber.e(
                        "RetryingDataSourceFactory Retry policy declined retry, throwing the last exception..."
                    )
                    throw ex
                }
            }
            Timber.e(
                "RetryingDataSourceFactory Max retries $maxRetries exceeded, throwing the last exception..."
            )
            throw lastException!!
        }
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

inline fun <reified T : Throwable> DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true
) = retryIf(maxRetries, printStackTrace, exponential) { ex -> ex.findCause<T>() != null }

@OptIn(UnstableApi::class)
fun DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true,
    predicate: (Throwable) -> Boolean
): DataSource.Factory = RetryingDataSourceFactory(this, maxRetries, printStackTrace, exponential, predicate)

@OptIn(UnstableApi::class)
class ConditionalCacheDataSourceFactory(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val shouldCache: (DataSpec) -> Boolean
) : DataSource.Factory {
    init {
        cacheDataSourceFactory.setUpstreamDataSourceFactory(upstreamDataSourceFactory)
    }

    override fun createDataSource() = object : DataSource {
        private lateinit var selectedFactory: DataSource.Factory
        private val transferListeners = mutableListOf<TransferListener>()

        private fun createSource(factory: DataSource.Factory = selectedFactory) =
            factory.createDataSource().apply {
                transferListeners.forEach { addTransferListener(it) }
            }

        private val open = AtomicBoolean(false)
        private var source by object : ReadWriteProperty<Any?, DataSource> {
            var s: DataSource? = null

            override fun getValue(thisRef: Any?, property: KProperty<*>) = s ?: run {
                val newSource = runCatching {
                    createSource()
                }.getOrElse {
                    if (it is UninitializedPropertyAccessException) throw PlaybackException(
                        "Illegal access of data source methods before calling open()",
                        it,
                        PlaybackException.ERROR_CODE_UNSPECIFIED
                    ) else throw it
                }
                s = newSource
                newSource
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: DataSource) {
                s = value
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) =
            source.read(buffer, offset, length)

        override fun addTransferListener(transferListener: TransferListener) {
            if (::selectedFactory.isInitialized) source.addTransferListener(transferListener)
            transferListeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            selectedFactory =
                if (shouldCache(dataSpec)) cacheDataSourceFactory else upstreamDataSourceFactory

            return runCatching {
                open.set(true)
                source.open(dataSpec)
            }.getOrElse {
                if (it is ReadOnlyException) {
                    source = createSource(upstreamDataSourceFactory)
                    source.open(dataSpec)
                } else throw it
            }
        }

        override fun getUri() = if (open.get()) source.uri else null
        override fun close() = if (open.compareAndSet(true, false)) {
            source.close()
        } else Unit
    }
}
