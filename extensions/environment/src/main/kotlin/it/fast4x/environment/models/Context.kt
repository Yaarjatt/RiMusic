package it.fast4x.environment.models

import io.ktor.http.headers
import it.fast4x.environment.Environment
import it.fast4x.environment.utils.EnvironmentLocale
import it.fast4x.environment.utils.EnvironmentPreferences
import it.fast4x.environment.utils.LocalePreferences
import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    private val request: Request = Request(),
    val user: User? = User()
) {

    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val platform: String? = null,
        val hl: String? = "en",
        val gl: String? = "US",
        val visitorData: String? = null,
        val androidSdkVersion: Int? = null,
        val userAgent: String? = null,
        val referer: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val acceptHeader: String? = null,
        val xClientName: Int? = null,

        val loginSupported: Boolean = false,
        val loginRequired: Boolean = false,
        val useSignatureTimestamp: Boolean = false,
        val useWebPoTokens: Boolean = false,
        val isEmbedded: Boolean = false,
        // val origin: String? = null,
        // val referer: String? = null,
    ){
        fun toContext(
            locale: EnvironmentLocale,
            visitorData: String?,
            //dataSyncId: String?
        ) = Context(
            client = Client(
                clientName = clientName,
                clientVersion = clientVersion,
                osVersion = osVersion,
                gl = locale.gl,
                hl = locale.hl,
                visitorData = visitorData
            ),
//            user = User(
//                onBehalfOfUser = dataSyncId
//            ),
        )
    }

    @Serializable
    data class ThirdParty(
        val embedUrl: String,
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false,
        val onBehalfOfUser: String? = null,
    )

    @Serializable
    data class Request(
        val internalExperimentFlags: Array<String> = emptyArray(),
        val useSsl: Boolean = true,
    )

    fun apply() {
        client.userAgent

        headers {
            client.referer?.let { append("Referer", it) }
            append("X-Youtube-Bootstrap-Logged-In", "false")
            append("X-YouTube-Client-Name", client.clientName)
            append("X-YouTube-Client-Version", client.clientVersion)
        }
    }



    companion object {

        val USER_AGENT = EnvironmentPreferences.preference?.p33 ?: ""
        val USER_AGENT1 = EnvironmentPreferences.preference?.p32 ?: ""

        val REFERER1 = EnvironmentPreferences.preference?.p34 ?: ""
        val REFERER2 = EnvironmentPreferences.preference?.p35 ?: ""

        val cname = EnvironmentPreferences.preference?.p18 ?: ""
        val cver = EnvironmentPreferences.preference?.p19 ?: ""
        val cplatform = EnvironmentPreferences.preference?.p20 ?: ""
        val cxname = EnvironmentPreferences.preference?.p21 ?: ""


        val DefaultWeb = Context(
            client = Client(
                clientName = cname,
                clientVersion = cver,
                //platform = cplatform,
                userAgent = USER_AGENT,
                //referer = REFERER1,
                visitorData = Environment.visitorData,
                xClientName = cxname.toIntOrNull(),
                loginSupported = true,
                useSignatureTimestamp = true,
                useWebPoTokens = true,
            )
        )


        val hl = LocalePreferences.preference?.hl
        //val gl = LocalePreferences.preference?.gl


        val DefaultWebWithLocale = DefaultWeb.copy(
            client = DefaultWeb.client.copy(hl = hl)
        )

        val cname2 = EnvironmentPreferences.preference?.p22 ?: ""
        val cver2 = EnvironmentPreferences.preference?.p23 ?: ""


        val DefaultWeb2 = Context(
            client = Client(
                clientName = cname2,
                clientVersion = cver2,
                userAgent = USER_AGENT,
                xClientName = 1
            )
        )

        val DefaultWeb2WithLocale = DefaultWeb2.copy(
            client = DefaultWeb2.client.copy(hl = hl)
        )

        val cname3 = EnvironmentPreferences.preference?.p24 ?: ""
        val cver3 = EnvironmentPreferences.preference?.p25 ?: ""
        val dmake = EnvironmentPreferences.preference?.p26 ?: ""
        val dmodel = EnvironmentPreferences.preference?.p27 ?: ""
        val osname = EnvironmentPreferences.preference?.p28 ?: ""
        val osversion = EnvironmentPreferences.preference?.p29 ?: ""
        val accept = EnvironmentPreferences.preference?.p30 ?: ""
        val cxname3 = EnvironmentPreferences.preference?.p31 ?: ""

        val DefaultWeb3 = Context(
            client = Client(
                clientName = cname3,
                clientVersion = cver3,
                //deviceMake = dmake,
                //deviceModel = dmodel,
                //osName = osname,
                osVersion = osversion,
                //acceptHeader = accept,
                userAgent = USER_AGENT1,
                xClientName = cxname3.toIntOrNull()
            )
        )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = Context(
            Client(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                loginSupported = true,
                loginRequired = true,
                useSignatureTimestamp = true,
                isEmbedded = true,
                xClientName = 85
            )
        )

        /**
         * Hardcoded IOS client fallback.
         * As of mid-2026 YouTube's /youtubei/v1/player returns direct playable audio URLs
         * (no poToken/signatureCipher) when called with the IOS client and a matching iOS UA / version.
         * This does not require login or a poToken and is used as a stream fallback when the
         * primary WEB_REMIX / potoken path fails (which often happens in self-built debug builds
         * or on devices with an outdated WebView).
         */
        val IOS = Context(
            Client(
                clientName = "IOS",
                clientVersion = "20.03.02",
                platform = "MOBILE",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                osName = "iOS",
                osVersion = "18.3.2.22D82",
                acceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                xClientName = 5,
                loginSupported = false,
                loginRequired = false,
                useSignatureTimestamp = false,
                useWebPoTokens = false,
                referer = "https://music.youtube.com/",
            )
        )

        /**
         * Hardcoded ANDROID_VR client fallback.
         * Same reasoning as IOS: returns direct audio URLs without poToken / login.
         * yt-dlp uses this client (clientName=ANDROID_VR, v1.60.19, x-client-name=28) very
         * successfully as its primary non-web client.
         */
        val ANDROID_VR = Context(
            Client(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                platform = "MOBILE",
                androidSdkVersion = 34,
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 14; SM-G998B Build/UP1A.231005.007) gzip",
                xClientName = 28,
                loginSupported = false,
                loginRequired = false,
                useSignatureTimestamp = false,
                useWebPoTokens = false,
                referer = "https://music.youtube.com/",
            )
        )

    }
}
