package com.hamurcuabi.usagelimit.data

import android.content.Context

/** Bir günün limit karnesi. */
data class DayRecord(
    /** O gün kaç kez bir limit doldu (uyarı çıktı). */
    val reached: Int = 0,
    /** O gün kaç kez ek süre alındı. */
    val extensions: Int = 0,
) {
    val state: DayState
        get() = when {
            extensions > 0 -> DayState.EXCEEDED
            reached > 0 -> DayState.KEPT
            else -> DayState.PERFECT
        }
}

enum class DayState {
    /** Hiçbir limit dolmadı. */
    PERFECT,

    /** Limit doldu ama ek süre alınmadı: sınır aşılmadı. */
    KEPT,

    /** Ek süre alındı: sınır aşıldı. */
    EXCEEDED;

    val success: Boolean get() = this != EXCEEDED
}

/**
 * İzlemenin çalıştığı günlerin kaydı; takvim, seri ve rozetler bundan hesaplanır.
 * Kayıt olmayan gün "veri yok" sayılır (izleme kapalıydı ya da telefon kullanılmadı).
 */
class HistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    private fun read(day: String): DayRecord? {
        val raw = prefs.getString(day, null) ?: return null
        val parts = raw.split(',')
        return DayRecord(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun write(day: String, record: DayRecord) {
        prefs.edit().putString(day, "${record.reached},${record.extensions}").apply()
    }

    /** İzleme o gün çalıştı; kayıt yoksa temiz bir kayıt açar. */
    fun markActive(day: String) {
        if (!prefs.contains(day)) write(day, DayRecord())
    }

    fun addReached(day: String) {
        val current = read(day) ?: DayRecord()
        write(day, current.copy(reached = current.reached + 1))
    }

    fun addExtension(day: String) {
        val current = read(day) ?: DayRecord()
        write(day, current.copy(extensions = current.extensions + 1))
    }

    fun all(): Map<String, DayRecord> {
        val result = HashMap<String, DayRecord>()
        for (key in prefs.all.keys) {
            read(key)?.let { result[key] = it }
        }
        return result
    }

    /** [KEEP_DAYS] günden eski kayıtları siler. */
    fun prune(now: Long = System.currentTimeMillis()) {
        val oldest = Time.dayKey(now - KEEP_DAYS * Time.DAY_MS)
        val stale = prefs.all.keys.filter { it < oldest }
        if (stale.isEmpty()) return
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val KEEP_DAYS = 400L
    }
}

data class Badge(val emoji: String, val title: String, val hint: String, val earned: Boolean)

/** Takvimden hesaplanan seri ve rozetler. */
data class Achievements(
    /** Bugüne kadar kesintisiz süren başarılı gün sayısı. */
    val streak: Int,
    val bestStreak: Int,
    val successDays: Int,
    val perfectDays: Int,
    val badges: List<Badge>,
) {
    companion object {
        fun from(calendar: Map<String, DayRecord>, now: Long = System.currentTimeMillis()): Achievements {
            // Günü ortasından al ki saat değişiklikleri gün kaydırmasın.
            val noon = Time.startOfDay(now) + Time.DAY_MS / 2
            fun key(daysAgo: Int) = Time.dayKey(noon - daysAgo * Time.DAY_MS)

            // Güncel seri: bugün henüz kayıt yoksa seriyi bozmaz, dünden sayılır.
            var streak = 0
            var daysAgo = 0
            val today = calendar[key(0)]
            if (today == null) daysAgo = 1
            while (true) {
                val record = calendar[key(daysAgo)] ?: break
                if (!record.state.success) break
                streak++
                daysAgo++
            }

            // En iyi seri: son 400 günü baştan sona tara.
            var best = 0
            var run = 0
            for (i in 400 downTo 0) {
                val record = calendar[key(i)]
                if (record != null && record.state.success) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }

            val success = calendar.values.count { it.state.success }
            val perfect = calendar.values.count { it.state == DayState.PERFECT }

            val badges = listOf(
                Badge("🌱", "İlk adım", "İlk başarılı gün", success >= 1),
                Badge("🔥", "3 gün seri", "Üst üste 3 gün", best >= 3),
                Badge("🏅", "Bir hafta", "Üst üste 7 gün", best >= 7),
                Badge("🏆", "İki hafta", "Üst üste 14 gün", best >= 14),
                Badge("👑", "Bir ay", "Üst üste 30 gün", best >= 30),
                Badge("⭐", "Kusursuz gün", "Hiçbir limit dolmadan bir gün", perfect >= 1),
                Badge("💎", "10 kusursuz", "10 kusursuz gün", perfect >= 10),
                Badge("🎯", "25 gün", "Toplam 25 başarılı gün", success >= 25),
            )
            return Achievements(streak, maxOf(best, streak), success, perfect, badges)
        }
    }
}
