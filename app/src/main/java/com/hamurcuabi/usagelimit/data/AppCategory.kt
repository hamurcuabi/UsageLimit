package com.hamurcuabi.usagelimit.data

import android.content.Context
import android.content.pm.ApplicationInfo

enum class AppCategory(val title: String) {
    SOCIAL("Sosyal medya"),
    GAME("Oyun"),
    MEDIA("Video ve müzik"),
    NEWS("Haber ve okuma"),
    PRODUCTIVITY("Üretkenlik ve araçlar"),
    OTHER("Diğer");

    companion object {
        /**
         * Uygulamalar kategorisini kendileri bildirir ama çoğu boş bırakır;
         * bu yüzden yaygın uygulamalar için elle eşleme de var.
         */
        private val known: Map<String, AppCategory> = buildMap {
            listOf(
                "com.instagram.android", "com.instagram.barcelona", "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill", "com.twitter.android", "com.facebook.katana",
                "com.facebook.lite", "com.facebook.orca", "com.snapchat.android",
                "com.reddit.frontpage", "com.linkedin.android", "com.pinterest",
                "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
                "org.thoughtcrime.securesms", "com.discord", "com.tumblr",
                "tv.twitch.android.app", "com.bereal.ft", "org.joinmastodon.android",
                "xyz.blueskyweb.app", "com.eksisozluk.eksisozluk", "com.bundle.app",
            ).forEach { put(it, SOCIAL) }
            listOf(
                "com.google.android.youtube", "com.google.android.apps.youtube.music",
                "com.netflix.mediaclient", "com.spotify.music", "com.amazon.avod.thirdpartyclient",
                "com.disney.disneyplus", "com.blutv.android", "tv.exxen.mobile",
                "com.gain.app", "com.turkcell.ott", "com.apple.android.music",
                "com.soundcloud.android", "deezer.android.app", "com.mubi",
                "com.google.android.apps.photos", "org.videolan.vlc",
            ).forEach { put(it, MEDIA) }
            listOf(
                "com.android.chrome", "com.google.android.gm", "com.google.android.calendar",
                "com.google.android.apps.docs", "com.google.android.keep", "com.microsoft.office.outlook",
                "com.microsoft.teams", "com.Slack", "com.google.android.apps.maps",
                "us.zoom.videomeetings", "com.notion.id", "org.mozilla.firefox",
                "com.brave.browser", "com.google.android.googlequicksearchbox",
            ).forEach { put(it, PRODUCTIVITY) }
        }

        fun of(context: Context, pkg: String): AppCategory {
            known[pkg]?.let { return it }
            val info = try {
                context.packageManager.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                return OTHER
            }
            @Suppress("DEPRECATION")
            if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return GAME
            return when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> GAME
                ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> MEDIA
                ApplicationInfo.CATEGORY_NEWS -> NEWS
                ApplicationInfo.CATEGORY_MAPS,
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
                else -> OTHER
            }
        }
    }
}
