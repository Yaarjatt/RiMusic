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
        val timeZone: String? = null,
        val utcOffsetMinutes: Int? = null,
        /** Origin/host to use in request header and URL (e.g. "www.youtube.com" vs "music.youtube.com"). */
        val apiHost: String? = null,

        val loginSupported: Boolean = false,
        val loginRequired: Boolean = false,
        val useSignatureTimestamp: Boolean = false,
        val useWebPoTokens: Boolean = false,
        val isEmbedded: Boolean = false,
        val sendPlaybackContext: Boolean = false,
        /**
         * When true, toContext() will NOT override hl/gl with the device locale.
         * Used by VISIONOS which must match yt-dlp's exact payload (hl="en", no gl).
         */
        val noLocaleOverrides: Boolean = false,
    ){
        fun toContext(
            locale: EnvironmentLocale,
            visitorData: String?,
            //dataSyncId: String?
        ) = Context(
            client = this.copy(
                gl = if (noLocaleOverrides) this.gl else locale.gl,
                hl = if (noLocaleOverrides) this.hl else locale.hl,
                visitorData = visitorData,
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
            // Match yt-dlp header spelling: "X-Youtube-Client-Name" (lowercase t in Youtube)
            append("X-Youtube-Client-Name", "${client.xClientName ?: 1}")
            append("X-Youtube-Client-Version", client.clientVersion)
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
         */
        val IOS = Context(
            Client(
                clientName = "IOS",
                clientVersion = "20.10.4",
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

        /**
         * VISIONOS client (Apple Vision Pro / visionOS Safari) — yt-dlp's working client as of Aug 2026.
         * Payload must match yt-dlp byte-for-byte to get unthrottled signed CDN URLs:
         *  - POST to https://www.youtube.com/youtubei/v1/player (NOT music.youtube.com)
         *  - X-Youtube-Client-Name: 101, X-Youtube-Client-Version: 1.02 (lowercase 't' in "Youtube")
         *  - Origin: https://www.youtube.com, Safari-on-macOS UA
         *  - client JSON: hl=en, timeZone=UTC, utcOffsetMinutes=0, no platform, no gl,
         *    userAgent set in the JSON body
         *  - playbackContext with html5Preference=HTML5_PREF_WANTS + fresh signatureTimestamp
         */
        val VISIONOS = Context(
            Client(
                clientName = "VISIONOS",
                clientVersion = "1.02",
                hl = "en",
                gl = null,
                platform = null,
                deviceMake = "Apple",
                deviceModel = "RealityDevice17,1",
                osName = "visionOS",
                osVersion = "26.5.23O471",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                xClientName = 101,
                timeZone = "UTC",
                utcOffsetMinutes = 0,
                apiHost = "www.youtube.com",
                referer = "https://www.youtube.com/",
                loginSupported = false,
                loginRequired = false,
                useSignatureTimestamp = true,
                useWebPoTokens = false,
                sendPlaybackContext = true,
                noLocaleOverrides = true,
            )
        )

    }
}
