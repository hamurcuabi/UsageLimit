package com.hamurcuabi.usagelimit.data

import android.content.Context

/**
 * Limit ayarları ve günlük sayaçlar. Servis de arayüz de aynı dosyayı okuduğu için
 * basit ve senkron olsun diye SharedPreferences kullanılıyor.
 */
class LimitStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("limits", Context.MODE_PRIVATE)

    var defaultLimitMin: Int
        get() = prefs.getInt(KEY_DEFAULT, 30)
        set(value) = prefs.edit().putInt(KEY_DEFAULT, value.coerceIn(MIN_LIMIT, MAX_LIMIT)).apply()

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING, false)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING, value).apply()

    /** Uygulamaya özel kural: null = varsayılanı kullan, [UNLIMITED] = sınırsız, >0 = dakika. */
    fun rule(pkg: String): Int? {
        val key = RULE_PREFIX + pkg
        return if (prefs.contains(key)) prefs.getInt(key, UNLIMITED) else null
    }

    fun setRule(pkg: String, rule: Int?) {
        val key = RULE_PREFIX + pkg
        val editor = prefs.edit()
        if (rule == null) editor.remove(key) else editor.putInt(key, rule)
        editor.apply()
    }

    /** Grubun (kategorinin) günlük toplam limiti; yoksa null. */
    fun groupLimit(category: String): Int? {
        val key = GROUP_PREFIX + category
        return if (prefs.contains(key)) prefs.getInt(key, 0).takeIf { it > 0 } else null
    }

    fun setGroupLimit(category: String, minutes: Int?) {
        val key = GROUP_PREFIX + category
        val editor = prefs.edit()
        if (minutes == null) editor.remove(key) else editor.putInt(key, minutes.coerceIn(MIN_LIMIT, MAX_LIMIT))
        editor.apply()
    }

    /** Hiçbir limite (uygulama veya grup) tabi olmayan uygulama mı? */
    fun isUnlimited(pkg: String, defaultExempt: Set<String>): Boolean {
        val rule = rule(pkg)
        return if (rule == null) pkg in defaultExempt else rule <= 0
    }

    /**
     * Uygulamanın kendi günlük limiti (dakika); yoksa null.
     * Özel kural yoksa: grubun toplam limiti varsa uygulama yalnızca ona tabidir,
     * yoksa varsayılan limit uygulanır.
     */
    fun limitMinutes(pkg: String, defaultExempt: Set<String>, category: String): Int? {
        val rule = rule(pkg)
        return when {
            rule == null -> when {
                pkg in defaultExempt -> null
                groupLimit(category) != null -> null
                else -> defaultLimitMin
            }
            rule <= 0 -> null
            else -> rule
        }
    }

    fun extensionsUsed(pkg: String, day: String): Int = prefs.getInt(dayKey(day, "ext", pkg), 0)

    fun addExtension(pkg: String, day: String) {
        prefs.edit().putInt(dayKey(day, "ext", pkg), extensionsUsed(pkg, day) + 1).apply()
    }

    fun wasWarned(pkg: String, day: String): Boolean = prefs.getBoolean(dayKey(day, "warn", pkg), false)

    fun markWarned(pkg: String, day: String) {
        prefs.edit().putBoolean(dayKey(day, "warn", pkg), true).apply()
    }

    /** Önceki günlere ait sayaçları temizler. */
    fun pruneOtherDays(today: String) {
        val keep = "$DAY_PREFIX$today:"
        val stale = prefs.all.keys.filter { it.startsWith(DAY_PREFIX) && !it.startsWith(keep) }
        if (stale.isEmpty()) return
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun dayKey(day: String, kind: String, pkg: String) = "$DAY_PREFIX$day:$kind:$pkg"

    companion object {
        const val UNLIMITED = -1
        const val MIN_LIMIT = 5
        const val MAX_LIMIT = 720
        const val STEP = 5

        /** Limit dolunca verilen ek süre ve günde uygulama başına kaç kez alınabileceği. */
        const val EXTENSION_MIN = 5
        const val MAX_EXTENSIONS = 2

        private const val KEY_DEFAULT = "default_limit_min"
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val RULE_PREFIX = "rule:"
        private const val GROUP_PREFIX = "group:"
        private const val DAY_PREFIX = "d:"
    }
}
