package com.hamurcuabi.usagelimit.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

/** Gün içinde yaşanan bir limit olayı. */
data class DayEvent(val at: Long, val label: String, val extension: Boolean)

/** Takvimde güne dokununca gösterilen ayrıntı. */
data class DayDetail(
    val totalMs: Long = 0L,
    /** En çok kullanılan uygulamalar: ad ve süre. */
    val topApps: List<Pair<String, Long>> = emptyList(),
    val events: List<DayEvent> = emptyList(),
) {
    fun toJson(): String {
        val apps = JSONArray()
        topApps.forEach { (label, ms) -> apps.put(JSONArray().put(label).put(ms)) }
        val list = JSONArray()
        events.forEach { list.put(JSONArray().put(it.at).put(it.label).put(if (it.extension) "x" else "r")) }
        return JSONObject().put("t", totalMs).put("a", apps).put("e", list).toString()
    }

    companion object {
        fun parse(raw: String): DayDetail? = try {
            val json = JSONObject(raw)
            val apps = json.optJSONArray("a") ?: JSONArray()
            val events = json.optJSONArray("e") ?: JSONArray()
            DayDetail(
                totalMs = json.optLong("t"),
                topApps = List(apps.length()) { i ->
                    val item = apps.getJSONArray(i)
                    item.getString(0) to item.getLong(1)
                },
                events = List(events.length()) { i ->
                    val item = events.getJSONArray(i)
                    DayEvent(item.getLong(0), item.getString(1), item.getString(2) == "x")
                },
            )
        } catch (_: Exception) {
            null
        }
    }
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

    private val detailPrefs =
        context.applicationContext.getSharedPreferences("history_detail", Context.MODE_PRIVATE)

    fun detail(day: String): DayDetail? = detailPrefs.getString(day, null)?.let { DayDetail.parse(it) }

    /** Günün toplam süresini ve en çok kullanılan uygulamalarını günceller. */
    fun saveUsage(day: String, totalMs: Long, topApps: List<Pair<String, Long>>) {
        val current = detail(day) ?: DayDetail()
        detailPrefs.edit().putString(day, current.copy(totalMs = totalMs, topApps = topApps).toJson()).apply()
    }

    fun addEvent(day: String, event: DayEvent) {
        val current = detail(day) ?: DayDetail()
        val events = (current.events + event).takeLast(MAX_EVENTS_PER_DAY)
        detailPrefs.edit().putString(day, current.copy(events = events).toJson()).apply()
    }

    fun details(): Map<String, DayDetail> {
        val result = HashMap<String, DayDetail>()
        for ((day, raw) in detailPrefs.all) {
            if (raw is String) DayDetail.parse(raw)?.let { result[day] = it }
        }
        return result
    }

    /** Son [days] günün ayrıntıları, buluta gönderilecek ham hâliyle. */
    fun rawDetails(now: Long, days: Int = 45): Map<String, String> {
        val oldest = Time.dayKey(now - days * Time.DAY_MS)
        val result = HashMap<String, String>()
        for ((day, raw) in detailPrefs.all) {
            if (raw is String && day >= oldest) result[day] = raw
        }
        return result
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
        for (store in listOf(prefs, detailPrefs)) {
            val stale = store.all.keys.filter { it < oldest }
            if (stale.isEmpty()) continue
            val editor = store.edit()
            stale.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    companion object {
        private const val KEEP_DAYS = 400L
        private const val MAX_EVENTS_PER_DAY = 60
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
