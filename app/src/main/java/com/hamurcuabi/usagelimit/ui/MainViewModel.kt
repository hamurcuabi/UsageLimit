package com.hamurcuabi.usagelimit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hamurcuabi.usagelimit.data.AppCategory
import com.hamurcuabi.usagelimit.data.Apps
import com.hamurcuabi.usagelimit.data.BatteryStore
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Permissions
import com.hamurcuabi.usagelimit.data.Time
import com.hamurcuabi.usagelimit.data.UsageReader
import com.hamurcuabi.usagelimit.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class AppRow(
    val pkg: String,
    val label: String,
    val category: AppCategory,
    val todayMs: Long,
    val sessionsToday: Int,
    /** Son 7 günün günlük ortalaması. */
    val dailyAvgMs: Long,
    /** Son 7 günde açılış başına ortalama süre. */
    val avgSessionMs: Long,
    /** Geçerli limit (dakika); sınırsızsa null. */
    val limitMin: Int?,
    /** Uygulamaya özel kural; null ise varsayılan geçerli. */
    val rule: Int?,
    /** Son 7 gün (bugün dahil, en sonda) kullanım süreleri. */
    val week: List<Long>,
    /** Hiçbir limite tabi değil (temel uygulama ya da elle sınırsız yapılmış). */
    val unlimited: Boolean = false,
    /** Kendi limiti yok, grubunun toplam limitine tabi. */
    val groupLimited: Boolean = false,
    val exceeded: Boolean = false,
    /** Uygulama ön plandayken düşen pil yüzdesi: bugün ve son 7 gün. */
    val batteryTodayPct: Float = 0f,
    val batteryWeekPct: Float = 0f,
) {
    val limitMs: Long? get() = limitMin?.let { it * Time.MINUTE_MS }
}

data class GroupInfo(
    /** Grubun toplam limiti (dakika); yoksa null. */
    val limitMin: Int?,
    /** Limite sayılan uygulamaların bugünkü toplamı. */
    val countedMs: Long,
) {
    val exceeded: Boolean get() = limitMin != null && countedMs >= limitMin * Time.MINUTE_MS
}

data class UiState(
    val loading: Boolean = true,
    val hasUsageAccess: Boolean = false,
    val canOverlay: Boolean = false,
    val canNotify: Boolean = false,
    val batteryOk: Boolean = false,
    val monitoring: Boolean = false,
    val defaultLimitMin: Int = 30,
    val totalTodayMs: Long = 0,
    val avgPerAppMs: Long = 0,
    val usedAppCount: Int = 0,
    val batteryTodayPct: Float = 0f,
    /** Son 7 günün toplamları (bugün en sonda) ve gün etiketleri. */
    val weekTotals: List<Long> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val rows: List<AppRow> = emptyList(),
    val groups: Map<AppCategory, GroupInfo> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LimitStore(app)
    private val batteryStore = BatteryStore(app)
    private val _state = MutableStateFlow(UiState(defaultLimitMin = store.defaultLimitMin))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val hasAccess = Permissions.hasUsageAccess(context)
            val base = _state.value.copy(
                loading = false,
                hasUsageAccess = hasAccess,
                canOverlay = Permissions.canOverlay(context),
                canNotify = Permissions.canNotify(context),
                batteryOk = Permissions.ignoresBatteryOptimizations(context),
                monitoring = store.monitoringEnabled,
                defaultLimitMin = store.defaultLimitMin,
            )
            if (!hasAccess) {
                _state.value = base.copy(rows = emptyList(), totalTodayMs = 0, avgPerAppMs = 0)
                return@launch
            }

            // İzleme açıksa ve servis sistem tarafından kapatıldıysa geri başlat.
            if (store.monitoringEnabled) {
                try {
                    MonitorService.start(context)
                } catch (_: Exception) {
                }
            }

            val now = System.currentTimeMillis()
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
            // Kurulu ve başlatılabilir bütün uygulamalar listelenir; kullanılmayanlar da dahil.
            val packages = Apps.launchable(context) - Apps.hidden(context)

            val base0 = packages.map { pkg ->
                val t = today[pkg]
                val w = weekEvents[pkg]
                val past = history.map { it[pkg] ?: 0L }
                val todayMs = t?.totalMs ?: 0L
                val category = AppCategory.of(context, pkg)
                val unlimited = store.isUnlimited(pkg, exempt)
                val limitMin = store.limitMinutes(pkg, exempt, category.name)
                AppRow(
                    pkg = pkg,
                    label = Apps.label(context, pkg),
                    category = category,
                    todayMs = todayMs,
                    sessionsToday = if (todayMs > 0) t?.sessions ?: 0 else 0,
                    dailyAvgMs = past.sum() / HISTORY_DAYS,
                    avgSessionMs = if (w != null && w.sessions > 0) w.totalMs / w.sessions else 0L,
                    limitMin = limitMin,
                    rule = store.rule(pkg),
                    week = past.takeLast(CHART_DAYS - 1) + todayMs,
                    unlimited = unlimited,
                    batteryTodayPct = batteryToday[pkg] ?: 0f,
                    batteryWeekPct = batteryWeek[pkg] ?: 0f,
                )
            }

            val groups = AppCategory.entries.associateWith { category ->
                GroupInfo(
                    limitMin = store.groupLimit(category.name),
                    countedMs = base0.filter { it.category == category && !it.unlimited }.sumOf { it.todayMs },
                )
            }

            val rows = base0
                .map { row ->
                    val group = groups.getValue(row.category)
                    val inGroup = !row.unlimited && group.limitMin != null
                    val ownExceeded = row.limitMs?.let { row.todayMs >= it } ?: false
                    row.copy(
                        groupLimited = inGroup && row.limitMin == null,
                        exceeded = ownExceeded || (inGroup && group.exceeded && row.todayMs > 0),
                    )
                }
                .sortedWith(
                    compareByDescending<AppRow> { it.todayMs }
                        .thenByDescending { it.dailyAvgMs }
                        .thenBy { it.label.lowercase() }
                )

            val used = rows.count { it.todayMs > 0 }
            val total = rows.sumOf { it.todayMs }
            _state.value = base.copy(
                rows = rows,
                groups = groups,
                totalTodayMs = total,
                usedAppCount = used,
                batteryTodayPct = rows.sumOf { it.batteryTodayPct.toDouble() }.toFloat(),
                avgPerAppMs = if (used > 0) total / used else 0L,
                weekTotals = List(CHART_DAYS) { i -> rows.sumOf { it.week[i] } },
                weekLabels = dayLabels(now),
            )
        }
    }

    fun changeDefaultLimit(delta: Int) {
        store.defaultLimitMin = store.defaultLimitMin + delta
        refresh()
    }

    fun setRule(pkg: String, rule: Int?) {
        store.setRule(pkg, rule)
        refresh()
    }

    fun setGroupLimit(category: AppCategory, minutes: Int?) {
        store.setGroupLimit(category.name, minutes)
        refresh()
    }

    fun setMonitoring(enabled: Boolean) {
        store.monitoringEnabled = enabled
        val context = getApplication<Application>()
        try {
            if (enabled) MonitorService.start(context) else MonitorService.stop(context)
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(monitoring = enabled)
    }

    private fun dayLabels(now: Long): List<String> {
        val names = listOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")
        val c = Calendar.getInstance()
        return List(CHART_DAYS) { i ->
            c.timeInMillis = now - (CHART_DAYS - 1 - i) * Time.DAY_MS
            names[c.get(Calendar.DAY_OF_WEEK) - 1]
        }
    }

    companion object {
        private const val HISTORY_DAYS = 7
        const val CHART_DAYS = 7
    }
}
