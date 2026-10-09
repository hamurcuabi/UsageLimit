package com.hamurcuabi.usagelimit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AppRow?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kullanım Limiti") }) },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !state.hasUsageAccess -> UsageAccessPrompt(Modifier.padding(padding)) {
                context.open(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = false)
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SummaryCard(state) }
                item {
                    LimitCard(
                        state = state,
                        onChange = vm::changeDefaultLimit,
                        onToggle = { enabled ->
                            if (enabled && !state.canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.setMonitoring(enabled)
                        },
                    )
                }
                if (!state.canOverlay || !state.canNotify || !state.batteryOk) {
                    item {
                        PermissionsCard(
                            state = state,
                            onOverlay = { context.open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
                            onNotify = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.openNotificationSettings()
                                }
                            },
                            onBattery = {
                                context.open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            },
                        )
                    }
                }
                item {
                    Text(
                        "Uygulamalar",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                if (state.rows.isEmpty()) {
                    item {
                        Text(
                            "Bugün henüz kullanım kaydı yok.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.rows, key = { it.pkg }) { row ->
                    AppRowItem(row) { editing = row }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    editing?.let { row ->
        RuleDialog(
            row = row,
            defaultLimitMin = state.defaultLimitMin,
            onDismiss = { editing = null },
            onSave = { rule ->
                vm.setRule(row.pkg, rule)
                editing = null
            },
        )
    }
}

@Composable
private fun UsageAccessPrompt(modifier: Modifier = Modifier, onGrant: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kullanım erişimi gerekli", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Hangi uygulamada ne kadar zaman geçirdiğini görebilmem için açılan listeden " +
                "\"Kullanım Limiti\"ni seçip erişime izin ver. Veriler telefondan çıkmaz.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Ayarları aç") }
    }
}

@Composable
private fun SummaryCard(state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Bugün toplam", style = MaterialTheme.typography.labelLarge)
            Text(
                Time.format(state.totalTodayMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            val used = state.rows.count { it.todayMs > 0 }
            Text(
                "$used uygulama · uygulama başına ortalama ${Time.format(state.avgPerAppMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LimitCard(state: UiState, onChange: (Int) -> Unit, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Varsayılan günlük limit", style = MaterialTheme.typography.labelLarge)
            Text(
                "Özel ayar vermediğin her uygulamaya uygulanır.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MinuteStepper(
                minutes = state.defaultLimitMin,
                onMinus = { onChange(-LimitStore.STEP) },
                onPlus = { onChange(LimitStore.STEP) },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("İzleme", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.monitoring) "Limit dolunca uyarı gösterilir." else "Kapalı: sadece istatistik görürsün.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.monitoring, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun MinuteStepper(minutes: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onMinus, enabled = minutes > LimitStore.MIN_LIMIT) { Text("−") }
        Text(
            "$minutes dk",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FilledTonalButton(onClick = onPlus, enabled = minutes < LimitStore.MAX_LIMIT) { Text("+") }
    }
}

@Composable
private fun PermissionsCard(
    state: UiState,
    onOverlay: () -> Unit,
    onNotify: () -> Unit,
    onBattery: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Eksik izinler", style = MaterialTheme.typography.labelLarge)
            if (!state.canOverlay) {
                PermissionRow("Diğer uygulamaların üzerinde göster", "Limit dolunca tam ekran uyarı için.", onOverlay)
            }
            if (!state.canNotify) {
                PermissionRow("Bildirimler", "Limit dolmadan önce haber vermek için.", onNotify)
            }
            if (!state.batteryOk) {
                PermissionRow("Pil optimizasyonu muafiyeti", "Sistem izlemeyi arka planda kapatmasın diye.", onBattery)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, reason: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(reason, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) { Text("İzin ver") }
    }
}

@Composable
private fun AppRowItem(row: AppRow, onClick: () -> Unit) {
    val limitMs = row.limitMin?.let { it * Time.MINUTE_MS }
    val exceeded = limitMs != null && row.todayMs >= limitMs
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (limitMs != null) "${Time.format(row.todayMs)} / ${row.limitMin} dk" else "${Time.format(row.todayMs)} · sınırsız",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (exceeded) FontWeight.Bold else FontWeight.Normal,
                        color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (limitMs != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (row.todayMs.toFloat() / limitMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Günlük ort. ${Time.format(row.dailyAvgMs)} · açılış başı ${Time.format(row.avgSessionMs)} · bugün ${row.sessionsToday} açılış",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bitmap = remember(pkg) {
        try {
            context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Spacer(Modifier.size(40.dp))
    }
}

private enum class RuleMode { DEFAULT, CUSTOM, UNLIMITED }

@Composable
private fun RuleDialog(
    row: AppRow,
    defaultLimitMin: Int,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    val initialMode = when {
        row.rule == null -> RuleMode.DEFAULT
        row.rule <= 0 -> RuleMode.UNLIMITED
        else -> RuleMode.CUSTOM
    }
    var mode by remember { mutableStateOf(initialMode) }
    var minutes by remember {
        mutableIntStateOf(if (row.rule != null && row.rule > 0) row.rule else defaultLimitMin)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                val defaultText = if (row.rule == null && row.limitMin == null) {
                    "Varsayılan (temel uygulama: sınırsız)"
                } else {
                    "Varsayılan ($defaultLimitMin dk)"
                }
                RuleOption(defaultText, mode == RuleMode.DEFAULT) { mode = RuleMode.DEFAULT }
                RuleOption("Özel limit", mode == RuleMode.CUSTOM) { mode = RuleMode.CUSTOM }
                if (mode == RuleMode.CUSTOM) {
                    Box(Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp)) {
                        MinuteStepper(
                            minutes = minutes,
                            onMinus = { minutes = (minutes - LimitStore.STEP).coerceAtLeast(LimitStore.MIN_LIMIT) },
                            onPlus = { minutes = (minutes + LimitStore.STEP).coerceAtMost(LimitStore.MAX_LIMIT) },
                        )
                    }
                }
                RuleOption("Sınırsız", mode == RuleMode.UNLIMITED) { mode = RuleMode.UNLIMITED }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    when (mode) {
                        RuleMode.DEFAULT -> null
                        RuleMode.CUSTOM -> minutes
                        RuleMode.UNLIMITED -> LimitStore.UNLIMITED
                    }
                )
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun RuleOption(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.open(action: String, withPackage: Boolean = true) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) intent.data = Uri.parse("package:$packageName")
    try {
        startActivity(intent)
    } catch (_: Exception) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}

private fun Context.openNotificationSettings() {
    try {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}
