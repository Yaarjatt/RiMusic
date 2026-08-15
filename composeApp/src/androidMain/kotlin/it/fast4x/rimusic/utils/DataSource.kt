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
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


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
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    )

/**
 * Build an OkHttpClient used for playback.
 *
 * We force a default bounded Range header on googlevideo CDN requests so that
 * ExoPlayer's initial probe (which uses no Range or open-ended "bytes=0-") does
 * not receive a 403. As of mid-2026 googlevideo returns HTTP 403 for any request
 * without a bounded Range header, including plain HEAD. Subsequent reads use
 * bounded ranges naturally (ExoPlayer ProgressiveMediaSource issues chunked reads
 * with bounded ranges) and will overwrite this default header with their own.
 */
@UnstableApi
fun buildPlaybackOkHttpClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host.endsWith(".googlevideo.com") && req.header("Range") == null) {
                // Force a bounded Range for the initial probe. ExoPlayer will replace this
                // with proper chunk ranges for subsequent reads (Range headers are overridden
                // by OkHttpDataSource if the DataSpec sets them).
                val newReq = req.newBuilder()
                    .header("Range", "bytes=0-1048575")
                    .build()
                chain.proceed(newReq)
            } else {
                chain.proceed(req)
            }
        }
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
