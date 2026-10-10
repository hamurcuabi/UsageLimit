package com.hamurcuabi.usagelimit.data

import android.content.Context
import java.util.Calendar

/**
 * Bir uygulamanın limitlerden bağımsız ham kullanım verisi.
 * Çocuğun telefonu bunu buluta yükler; ebeveyn aynı veriyi kendi koyduğu kurallarla yorumlar.
 */
data class RawApp(
    val pkg: String,
    val label: String,
    val category: AppCategory,
    val todayMs: Long,
    val sessionsToday: Int,
    val dailyAvgMs: Long,
    val avgSessionMs: Long,
    /** Son 7 gün, bugün en sonda. */
    val week: List<Long>,
    val batteryTodayPct: Float,
    val batteryWeekPct: Float,
    /** Telefon, ayarlar gibi varsayılan olarak sınırsız kalan temel uygulama. */
    val exempt: Boolean,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "pkg" to pkg,
        "label" to label,
        "category" to category.name,
        "todayMs" to todayMs,
        "sessionsToday" to sessionsToday,
        "dailyAvgMs" to dailyAvgMs,
        "avgSessionMs" to avgSessionMs,
        "week" to week,
        "batteryTodayPct" to batteryTodayPct.toDouble(),
        "batteryWeekPct" to batteryWeekPct.toDouble(),
        "exempt" to exempt,
    )

    companion object {
        fun fromMap(map: Map<*, *>): RawApp? {
            val pkg = map["pkg"] as? String ?: return null
            val categoryName = map["category"] as? String
            val week = (map["week"] as? List<*>)?.map { (it as? Number)?.toLong() ?: 0L } ?: emptyList()
            return RawApp(
                pkg = pkg,
                label = map["label"] as? String ?: pkg,
                category = AppCategory.entries.firstOrNull { it.name == categoryName } ?: AppCategory.OTHER,
                todayMs = (map["todayMs"] as? Number)?.toLong() ?: 0L,
                sessionsToday = (map["sessionsToday"] as? Number)?.toInt() ?: 0,
                dailyAvgMs = (map["dailyAvgMs"] as? Number)?.toLong() ?: 0L,
                avgSessionMs = (map["avgSessionMs"] as? Number)?.toLong() ?: 0L,
                week = List(UsageCollector.CHART_DAYS) { i ->
                    week.getOrElse(week.size - UsageCollector.CHART_DAYS + i) { 0L }
                },
                batteryTodayPct = (map["batteryTodayPct"] as? Number)?.toFloat() ?: 0f,
                batteryWeekPct = (map["batteryWeekPct"] as? Number)?.toFloat() ?: 0f,
                exempt = map["exempt"] as? Boolean ?: false,
            )
        }
    }
}

object UsageCollector {
    private const val HISTORY_DAYS = 7
    const val CHART_DAYS = 7

    /** Kurulu ve başlatılabilir bütün uygulamaların kullanım verisi (kullanılmayanlar dahil). */
    fun collect(context: Context, batteryStore: BatteryStore, now: Long = System.currentTimeMillis()): List<RawApp> {
        val dayStart = Time.startOfDay(now)
        val today = UsageReader.read(context, dayStart, now).perApp
        val weekEvents = UsageReader.read(context, dayStart - 6 * Time.DAY_MS, now).perApp
        val history = UsageReader.dailyHistory(context, HISTORY_DAYS)

        val batteryToday = batteryStore.day(Time.dayKey(now))
        val batteryWeek = HashMap<String, Float>()
        for (i in 0 until CHART_DAYS) {
            for ((pkg, pct) in batteryStore.day(Time.dayKey(now - i * Time.DAY_MS))) {
                batteryWeek[pkg] = (batteryWeek[pkg] ?: 0f) + pct
            }
        }

        val exempt = Apps.defaultExempt(context)
        val packages = Apps.launchable(context) - Apps.hidden(context)

        return packages.map { pkg ->
            val t = today[pkg]
            val w = weekEvents[pkg]
            val past = history.map { it[pkg] ?: 0L }
            val todayMs = t?.totalMs ?: 0L
            RawApp(
                pkg = pkg,
                label = Apps.label(context, pkg),
                category = AppCategory.of(context, pkg),
                todayMs = todayMs,
                sessionsToday = if (todayMs > 0) t?.sessions ?: 0 else 0,
                dailyAvgMs = past.sum() / HISTORY_DAYS,
                avgSessionMs = if (w != null && w.sessions > 0) w.totalMs / w.sessions else 0L,
                week = past.takeLast(CHART_DAYS - 1) + todayMs,
                batteryTodayPct = batteryToday[pkg] ?: 0f,
                batteryWeekPct = batteryWeek[pkg] ?: 0f,
                exempt = pkg in exempt,
            )
        }
    }

    fun dayLabels(now: Long = System.currentTimeMillis()): List<String> {
        val names = listOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")
        val c = Calendar.getInstance()
        return List(CHART_DAYS) { i ->
            c.timeInMillis = now - (CHART_DAYS - 1 - i) * Time.DAY_MS
            names[c.get(Calendar.DAY_OF_WEEK) - 1]
        }
    }
}
