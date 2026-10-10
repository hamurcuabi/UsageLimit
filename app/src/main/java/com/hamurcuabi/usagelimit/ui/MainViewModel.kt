package com.hamurcuabi.usagelimit.ui

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hamurcuabi.usagelimit.data.AppCategory
import com.hamurcuabi.usagelimit.data.BatteryStore
import com.hamurcuabi.usagelimit.data.DayDetail
import com.hamurcuabi.usagelimit.data.DayRecord
import com.hamurcuabi.usagelimit.data.HistoryStore
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Permissions
import com.hamurcuabi.usagelimit.data.RawApp
import com.hamurcuabi.usagelimit.data.RoleStore
import com.hamurcuabi.usagelimit.data.RuleSet
import com.hamurcuabi.usagelimit.data.Time
import com.hamurcuabi.usagelimit.data.UsageCollector
import com.hamurcuabi.usagelimit.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val maxExtensions: Int = LimitStore.DEFAULT_MAX_EXTENSIONS,
    val extensionMin: Int = LimitStore.DEFAULT_EXTENSION_MIN,
    val totalTodayMs: Long = 0,
    val avgPerAppMs: Long = 0,
    val usedAppCount: Int = 0,
    val batteryTodayPct: Float = 0f,
    val charging: Boolean = false,
    /** Son 7 günün toplamları (bugün en sonda) ve gün etiketleri. */
    val weekTotals: List<Long> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val rows: List<AppRow> = emptyList(),
    val groups: Map<AppCategory, GroupInfo> = emptyMap(),
    /** Gün (yyyyMMdd) -> o günün limit karnesi; takvim ve rozetler için. */
    val calendar: Map<String, DayRecord> = emptyMap(),
    val dayDetails: Map<String, DayDetail> = emptyMap(),
)

/**
 * Ham kullanım verisini kurallarla birleştirip ekranın göstereceği hâle getirir.
 * Hem bu telefonun verisi hem de ebeveynin uzaktan gördüğü çocuk verisi için kullanılır.
 */
fun UiState.withData(apps: List<RawApp>, rules: RuleSet, weekLabels: List<String>): UiState {
    val base = apps.map { app ->
        AppRow(
            pkg = app.pkg,
            label = app.label,
            category = app.category,
            todayMs = app.todayMs,
            sessionsToday = app.sessionsToday,
            dailyAvgMs = app.dailyAvgMs,
            avgSessionMs = app.avgSessionMs,
            limitMin = rules.limitMinutes(app.pkg, app.exempt, app.category.name),
            rule = rules.rule(app.pkg),
            week = app.week,
            unlimited = rules.isUnlimited(app.pkg, app.exempt),
            batteryTodayPct = app.batteryTodayPct,
            batteryWeekPct = app.batteryWeekPct,
        )
    }

    val groups = AppCategory.entries.associateWith { category ->
        GroupInfo(
            limitMin = rules.groupLimit(category.name),
            countedMs = base.filter { it.category == category && !it.unlimited }.sumOf { it.todayMs },
        )
    }

    val rows = base
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
    return copy(
        defaultLimitMin = rules.defaultLimitMin,
        maxExtensions = rules.maxExtensions,
        extensionMin = rules.extensionMin,
        rows = rows,
        groups = groups,
        totalTodayMs = total,
        usedAppCount = used,
        batteryTodayPct = rows.sumOf { it.batteryTodayPct.toDouble() }.toFloat(),
        avgPerAppMs = if (used > 0) total / used else 0L,
        weekTotals = List(UsageCollector.CHART_DAYS) { i -> rows.sumOf { it.week.getOrElse(i) { 0L } } },
        weekLabels = weekLabels,
    )
}

/** Bu telefonun kendi verisi: tek başına kullanımda ve çocuk modunda. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LimitStore(app)
    private val batteryStore = BatteryStore(app)
    private val historyStore = HistoryStore(app)
    private val roles = RoleStore(app)
    private val _state = MutableStateFlow(UiState(defaultLimitMin = store.defaultLimitMin))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val hasAccess = Permissions.hasUsageAccess(context)

            // Çocuğun telefonunda izleme kapatılamaz.
            if (roles.isPairedChild && !store.monitoringEnabled) store.monitoringEnabled = true

            val base = _state.value.copy(
                loading = false,
                hasUsageAccess = hasAccess,
                canOverlay = Permissions.canOverlay(context),
                canNotify = Permissions.canNotify(context),
                batteryOk = Permissions.ignoresBatteryOptimizations(context),
                monitoring = store.monitoringEnabled,
                defaultLimitMin = store.defaultLimitMin,
                calendar = historyStore.all(),
                dayDetails = historyStore.details(),
                charging = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging,
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
            val apps = UsageCollector.collect(context, batteryStore, now)
            _state.value = base.withData(apps, store.rules(), UsageCollector.dayLabels(now))
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

    fun changeMaxExtensions(delta: Int) {
        store.maxExtensions = store.maxExtensions + delta
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
}
