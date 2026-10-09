package com.hamurcuabi.usagelimit.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

object Apps {
    /** Listede hiç gösterilmeyen ve asla sınırlanmayan paketler (kendimiz, ana ekran, sistem arayüzü). */
    fun hidden(context: Context): Set<String> {
        val result = HashSet<String>()
        result += context.packageName
        result += "com.android.systemui"
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { result += it.activityInfo.packageName }
        return result
    }

    /** Özel kural verilmedikçe sınırsız kalan temel uygulamalar. */
    fun defaultExempt(context: Context): Set<String> {
        val result = HashSet<String>(hidden(context))
        result += listOf(
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.incallui",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecom?.defaultDialerPackage?.let { result += it }
        return result
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun isLaunchable(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null
}
