package com.hamurcuabi.usagelimit.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

data class AppUsage(val totalMs: Long, val sessions: Int)

data class UsageSnapshot(
    val perApp: Map<String, AppUsage>,
    /** Aralığın sonunda ön planda olan uygulama; yoksa null. */
    val foreground: String?,
)

/**
 * Kullanım sürelerini sistemin olay kaydından (activity resumed / paused) hesaplar.
 * Bu, anlık ön plan uygulamasını da verdiği için limit takibinde UsageStats
 * özetlerinden daha güncel sonuç üretir.
 */
object UsageReader {
    private const val ACTIVITY_RESUMED = 1
    private const val ACTIVITY_PAUSED = 2
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26

    /** Gün başlamadan önce açılmış bir uygulamayı yakalamak için geriye bakılan süre. */
    private const val LOOKBACK_MS = 2 * 60 * 60 * 1000L

    /** Bu süreden kısa aralar aynı oturum sayılır (uygulama içi ekran geçişleri). */
    private const val SAME_SESSION_GAP_MS = 3_000L

    fun read(context: Context, start: Long, end: Long): UsageSnapshot {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(start - LOOKBACK_MS, end)
        } catch (_: Exception) {
            null
        } ?: return UsageSnapshot(emptyMap(), null)

        val totals = HashMap<String, Long>()
        val sessions = HashMap<String, Int>()

        var current: String? = null
        var currentStart = 0L
        val resumedActivities = HashSet<String>()
        var lastClosedPkg: String? = null
        var lastClosedAt = 0L

        fun close(at: Long) {
            val pkg = current ?: return
            val from = maxOf(currentStart, start)
            if (at > from) totals[pkg] = (totals[pkg] ?: 0L) + (at - from)
            lastClosedPkg = pkg
            lastClosedAt = at
            current = null
            resumedActivities.clear()
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val ts = e.timeStamp
            when (e.eventType) {
                ACTIVITY_RESUMED -> {
                    if (current != pkg) {
                        close(ts)
                        val continues = pkg == lastClosedPkg && ts - lastClosedAt < SAME_SESSION_GAP_MS
                        if (!continues && ts >= start) {
                            sessions[pkg] = (sessions[pkg] ?: 0) + 1
                        }
                        current = pkg
                        currentStart = ts
                    }
                    resumedActivities.add(e.className ?: "")
                }

                ACTIVITY_PAUSED -> {
                    if (current == pkg) {
                        resumedActivities.remove(e.className ?: "")
                        if (resumedActivities.isEmpty()) close(ts)
                    }
                }

                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> close(ts)
            }
        }

        val foreground = current
        if (foreground != null) {
            val from = maxOf(currentStart, start)
            if (end > from) totals[foreground] = (totals[foreground] ?: 0L) + (end - from)
        }

        val result = HashMap<String, AppUsage>(totals.size)
        for ((pkg, ms) in totals) {
            // Gün başlamadan açılıp devam eden kullanım en az bir oturum sayılır.
            result[pkg] = AppUsage(ms, maxOf(sessions[pkg] ?: 0, 1))
        }
        return UsageSnapshot(result, foreground)
    }

    /** Bugünden önceki [days] günün uygulama başına günlük ortalaması (ms). */
    fun dailyAverages(context: Context, days: Int = 7): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = Time.startOfDay()
        val start = end - days * Time.DAY_MS
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()

        val sums = HashMap<String, Long>()
        for (s in stats) {
            // Bugünün (henüz bitmemiş) kovasını ortalamaya katma.
            if (s.firstTimeStamp >= end) continue
            if (s.totalTimeInForeground <= 0) continue
            sums[s.packageName] = (sums[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        return sums.mapValues { it.value / days }
    }
}
