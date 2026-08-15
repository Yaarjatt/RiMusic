package it.fast4x.environment.utils

import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import io.ktor.util.toMap
import it.fast4x.environment.Environment
import it.fast4x.environment.models.Context
import it.fast4x.environment.models.PlayerResponse
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, dataToSend?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", Context.USER_AGENT)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("NewPipe in Environment reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }

}

object NewPipeUtils {

    init {
        NewPipe.init(NewPipeDownloaderImpl(Environment.proxy))
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }.onFailure {
        println("NewPipeUtils getSignatureTimestamp failed for $videoId: ${it.message}")
        it.printStackTrace()
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            val rawUrl = format.url
                ?: format.signatureCipher?.let { decodeSignatureCipher(videoId, it) }
                ?: throw ParsingException("NewPipe in Environment Could not find format url or signatureCipher")

            // Always run through NewPipeExtractor's throttling-parameter (n-param) deobfuscator.
            // As of mid-2026 YouTube applies n-parameter throttling to direct (non-signatureCipher)
            // URLs as well: without decoding, googlevideo returns HTTP 403 on chunks past ~1 MB.
            // This call fetches the player JS (cached per client version), runs the n-decoder,
            // and returns the fixed URL. Fail soft to the raw URL rather than breaking playback.
            return@runCatching try {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, rawUrl)
            } catch (t: Throwable) {
                println("NewPipeUtils n-deobfuscation failed for $videoId: ${t.message}, using raw URL")
                t.printStackTrace()
                rawUrl
            }
        }

    fun decodeSignatureCipher(
        videoId: String,
        signatureCipher: String,
    ): String =
        signatureCipher.let { signature ->
            val params = parseQueryString(signature)
            println("NewPipe in Environment decodeSignatureCipher params ${params.toMap().map { it.key }}")
            val obfuscatedSignature = params["s"]
                ?: throw ParsingException("NewPipe in Environment decodeSignatureCipher Could not parse cipher signature")
            val signatureParam = params["sp"]
                ?: throw ParsingException("NewPipe in Environment decodeSignatureCipher Could not parse cipher signature parameter")
            val url = params["url"]?.let { URLBuilder(it) }
                ?: throw ParsingException("NewPipe in Environment decodeSignatureCipher Could not parse cipher url")
            url.parameters[signatureParam] =
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                    videoId,
                    obfuscatedSignature
                )
            println("NewPipe in Environment decodeSignatureCipher url.parameters ${url.parameters.entries().map { it.key }}")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url.toString()
            )
        }

}
