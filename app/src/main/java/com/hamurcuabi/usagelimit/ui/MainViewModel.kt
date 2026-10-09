package com.hamurcuabi.usagelimit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hamurcuabi.usagelimit.data.Apps
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

data class AppRow(
    val pkg: String,
    val label: String,
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
)

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
    val rows: List<AppRow> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LimitStore(app)
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
            val today = UsageReader.read(context, Time.startOfDay(now), now).perApp
            val week = UsageReader.read(context, Time.startOfDay(now) - 6 * Time.DAY_MS, now).perApp
            val dailyAvg = UsageReader.dailyAverages(context, 7)

            val hidden = Apps.hidden(context)
            val exempt = Apps.defaultExempt(context)

            val rows = (today.keys + dailyAvg.filterValues { it >= Time.MINUTE_MS }.keys)
                .asSequence()
                .filter { it !in hidden && Apps.isLaunchable(context, it) }
                .map { pkg ->
                    val t = today[pkg]
                    val w = week[pkg]
                    AppRow(
                        pkg = pkg,
                        label = Apps.label(context, pkg),
                        todayMs = t?.totalMs ?: 0L,
                        sessionsToday = if (t != null && t.totalMs > 0) t.sessions else 0,
                        dailyAvgMs = dailyAvg[pkg] ?: 0L,
                        avgSessionMs = if (w != null && w.sessions > 0) w.totalMs / w.sessions else 0L,
                        limitMin = store.limitMinutes(pkg, exempt),
                        rule = store.rule(pkg),
                    )
                }
                .sortedWith(compareByDescending<AppRow> { it.todayMs }.thenByDescending { it.dailyAvgMs })
                .toList()

            val usedToday = rows.filter { it.todayMs > 0 }
            val total = usedToday.sumOf { it.todayMs }
            _state.value = base.copy(
                rows = rows,
                totalTodayMs = total,
                avgPerAppMs = if (usedToday.isNotEmpty()) total / usedToday.size else 0L,
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
