package com.hamurcuabi.usagelimit.data

/**
 * Bütün limit ayarlarının taşınabilir hâli. Yerelde [LimitStore]'da durur;
 * ebeveyn/çocuk kullanımında Firestore'da saklanır ve çocuğun telefonuna aynalanır.
 */
data class RuleSet(
    val defaultLimitMin: Int = 30,
    /** Uygulamaya özel kural: [LimitStore.UNLIMITED] = sınırsız, >0 = dakika. Kayıt yoksa varsayılan. */
    val appRules: Map<String, Int> = emptyMap(),
    /** Kategori adı -> grubun günlük toplam limiti (dakika). */
    val groupLimits: Map<String, Int> = emptyMap(),
    /** Limit dolunca günde (uygulama/grup başına) kaç kez ek süre alınabilir. */
    val maxExtensions: Int = LimitStore.DEFAULT_MAX_EXTENSIONS,
    val extensionMin: Int = LimitStore.DEFAULT_EXTENSION_MIN,
) {
    fun rule(pkg: String): Int? = appRules[pkg]

    fun groupLimit(category: String): Int? = groupLimits[category]?.takeIf { it > 0 }

    fun isUnlimited(pkg: String, exempt: Boolean): Boolean {
        val rule = appRules[pkg]
        return if (rule == null) exempt else rule <= 0
    }

    /** Uygulamanın kendi günlük limiti; yoksa null. [LimitStore.limitMinutes] ile aynı mantık. */
    fun limitMinutes(pkg: String, exempt: Boolean, category: String): Int? {
        val rule = appRules[pkg]
        return when {
            rule == null -> when {
                exempt -> null
                groupLimit(category) != null -> null
                else -> defaultLimitMin
            }
            rule <= 0 -> null
            else -> rule
        }
    }

    fun withRule(pkg: String, rule: Int?): RuleSet =
        copy(appRules = if (rule == null) appRules - pkg else appRules + (pkg to rule))

    fun withGroupLimit(category: String, minutes: Int?): RuleSet =
        copy(groupLimits = if (minutes == null) groupLimits - category else groupLimits + (category to minutes))

    fun toMap(): Map<String, Any> = mapOf(
        "defaultLimitMin" to defaultLimitMin,
        "appRules" to appRules,
        "groupLimits" to groupLimits,
        "maxExtensions" to maxExtensions,
        "extensionMin" to extensionMin,
        "updatedAt" to System.currentTimeMillis(),
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): RuleSet = RuleSet(
            defaultLimitMin = (map["defaultLimitMin"] as? Number)?.toInt() ?: 30,
            appRules = intMap(map["appRules"]),
            groupLimits = intMap(map["groupLimits"]),
            maxExtensions = (map["maxExtensions"] as? Number)?.toInt() ?: LimitStore.DEFAULT_MAX_EXTENSIONS,
            extensionMin = (map["extensionMin"] as? Number)?.toInt() ?: LimitStore.DEFAULT_EXTENSION_MIN,
        )

        private fun intMap(value: Any?): Map<String, Int> {
            val source = value as? Map<*, *> ?: return emptyMap()
            val result = HashMap<String, Int>()
            for ((k, v) in source) {
                if (k is String && v is Number) result[k] = v.toInt()
            }
            return result
        }
    }
}
