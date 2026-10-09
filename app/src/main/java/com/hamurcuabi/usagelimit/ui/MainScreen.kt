@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamurcuabi.usagelimit.data.AppCategory
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val COLLAPSED_ROWS = 4

private fun AppCategory.color(): Color = when (this) {
    AppCategory.SOCIAL -> Color(0xFFE86A92)
    AppCategory.GAME -> Color(0xFF8B7CF6)
    AppCategory.MEDIA -> Color(0xFF35B3A6)
    AppCategory.NEWS -> Color(0xFF5B9BE6)
    AppCategory.PRODUCTIVITY -> Color(0xFF8DBB4B)
    AppCategory.OTHER -> Color(0xFF8894A1)
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var editingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<AppCategory>()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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

            else -> {
                val groups = remember(state.rows) {
                    state.rows.groupBy { it.category }
                        .toList()
                        .sortedWith(
                            compareByDescending<Pair<AppCategory, List<AppRow>>> { g -> g.second.sumOf { it.todayMs } }
                                .thenBy { it.first.ordinal }
                        )
                }
                val trimmed = query.trim()
                val matches = remember(state.rows, trimmed) {
                    if (trimmed.isEmpty()) emptyList()
                    else state.rows.filter { it.label.contains(trimmed, ignoreCase = true) }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                ) {
                    item(key = "header") { Header() }
                    item(key = "hero") { HeroCard(state, groups) }

                    if (!state.canOverlay || !state.canNotify || !state.batteryOk) {
                        item(key = "permissions") {
                            Spacer(Modifier.height(12.dp))
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

                    item(key = "control") {
                        Spacer(Modifier.height(12.dp))
                        ControlCard(
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

                    item(key = "search") {
                        Spacer(Modifier.height(20.dp))
                        SearchField(query) { query = it }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (trimmed.isNotEmpty()) {
                        if (matches.isEmpty()) {
                            item(key = "no-match") {
                                Text(
                                    "\"$trimmed\" ile eşleşen uygulama yok.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        items(matches, key = { "m-" + it.pkg }) { row ->
                            AppRowItem(
                                row = row,
                                onClick = { editingPkg = row.pkg },
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    } else {
                        groups.forEach { (category, rows) ->
                            val used = rows.filter { it.todayMs > 0 }
                            val isExpanded = category in expanded
                            val visible = if (isExpanded) rows else used.take(COLLAPSED_ROWS)
                            val hidden = rows.size - visible.size

                            item(key = "h-" + category.name) {
                                GroupHeader(
                                    category = category,
                                    rows = rows,
                                    usedCount = used.size,
                                    info = state.groups[category],
                                    onClick = { editingGroup = category.name },
                                )
                            }
                            items(visible, key = { it.pkg }) { row ->
                                AppRowItem(
                                    row = row,
                                    onClick = { editingPkg = row.pkg },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                                )
                            }
                            item(key = "f-" + category.name) {
                                GroupFooter(
                                    hidden = hidden,
                                    expanded = isExpanded,
                                    canCollapse = rows.size > used.take(COLLAPSED_ROWS).size,
                                    onToggle = {
                                        expanded = if (isExpanded) expanded - category else expanded + category
                                    },
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    val group = editingGroup?.let { name -> AppCategory.entries.firstOrNull { it.name == name } }
    if (group != null) {
        ModalBottomSheet(
            onDismissRequest = { editingGroup = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            GroupDetail(
                category = group,
                info = state.groups[group] ?: GroupInfo(null, 0L),
                appCount = state.rows.count { it.category == group },
                defaultLimitMin = state.defaultLimitMin,
                onLimit = { vm.setGroupLimit(group, it) },
            )
        }
    }

    val editing = editingPkg?.let { pkg -> state.rows.firstOrNull { it.pkg == pkg } }
    if (editing != null) {
        ModalBottomSheet(
            onDismissRequest = { editingPkg = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AppDetail(
                row = editing,
                labels = state.weekLabels,
                defaultLimitMin = state.defaultLimitMin,
                onRule = { vm.setRule(editing.pkg, it) },
            )
        }
    }
}

@Composable
private fun Header() {
    val date = remember {
        SimpleDateFormat("d MMMM EEEE", Locale.forLanguageTag("tr")).format(Date())
    }
    Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            "Kullanım Limiti",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(date, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UsageAccessPrompt(modifier: Modifier = Modifier, onGrant: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⏳", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Önce kullanım erişimi gerekiyor",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Hangi uygulamada ne kadar zaman geçirdiğini görebilmek için açılan listeden " +
                "\"Kullanım Limiti\"ni seçip erişime izin ver. Veriler telefondan çıkmaz.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text("Ayarları aç", modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun HeroCard(state: UiState, groups: List<Pair<AppCategory, List<AppRow>>>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Bugün ekran başında",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Time.format(state.totalTodayMs),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${state.usedAppCount} uygulama · uygulama başına ortalama ${Time.format(state.avgPerAppMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val shares = groups
                .map { (category, rows) -> category to rows.sumOf { it.todayMs } }
                .filter { it.second > 0 }
            if (shares.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    shares.forEach { (category, ms) ->
                        Box(
                            Modifier
                                .weight(ms.toFloat().coerceAtLeast(1f))
                                .fillMaxHeight()
                                .background(category.color())
                        )
                    }
                }
            }

            if (state.weekTotals.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                WeekBars(state.weekTotals, state.weekLabels)
            }
        }
    }
}

@Composable
private fun WeekBars(values: List<Long>, labels: List<String>, barHeight: Dp = 64.dp) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { i, value ->
            val isToday = i == values.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((value.toFloat() / max).coerceIn(0.04f, 1f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isToday) accent else accent.copy(alpha = 0.28f))
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    labels.getOrElse(i) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlCard(state: UiState, onChange: (Int) -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("İzleme", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.monitoring) "Limit dolunca uyarı çıkar" else "Kapalı, sadece istatistik",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.monitoring, onCheckedChange = onToggle)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Varsayılan limit", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Uygulama başına, günlük",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MinuteStepper(
                    minutes = state.defaultLimitMin,
                    onMinus = { onChange(-LimitStore.STEP) },
                    onPlus = { onChange(LimitStore.STEP) },
                )
            }
        }
    }
}

@Composable
private fun MinuteStepper(minutes: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = minutes > LimitStore.MIN_LIMIT, onClick = onMinus)
        Text(
            "$minutes dk",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        StepButton("+", enabled = minutes < LimitStore.MAX_LIMIT, onClick = onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun PermissionsCard(
    state: UiState,
    onOverlay: () -> Unit,
    onNotify: () -> Unit,
    onBattery: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Text("Uyarıların çalışması için", style = MaterialTheme.typography.titleSmall)
            if (!state.canOverlay) PermissionRow("Üstte gösterme", "Tam ekran limit uyarısı", onOverlay)
            if (!state.canNotify) PermissionRow("Bildirimler", "Limit dolmadan haber verir", onNotify)
            if (!state.batteryOk) PermissionRow("Pil muafiyeti", "İzleme arka planda kapanmaz", onBattery)
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
        TextButton(onClick = onClick) { Text("İzin ver") }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Uygulama ara") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Temizle")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GroupHeader(
    category: AppCategory,
    rows: List<AppRow>,
    usedCount: Int,
    info: GroupInfo?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val total = rows.sumOf { it.todayMs }
    val limitMin = info?.limitMin
    val exceeded = info?.exceeded == true
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(category.color())
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (usedCount > 0) "${rows.size} uygulama · bugün $usedCount tanesi açıldı" else "${rows.size} uygulama · bugün açılmadı",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Time.format(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (exceeded) scheme.error else scheme.onSurface,
                )
                Text(
                    if (limitMin != null) "/ $limitMin dk" else "Grup limiti koy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (limitMin != null) scheme.onSurfaceVariant else scheme.primary,
                )
            }
        }
        if (limitMin != null && info != null) {
            Spacer(Modifier.height(10.dp))
            ProgressTrack(
                fraction = (info.countedMs.toFloat() / (limitMin * Time.MINUTE_MS)).coerceIn(0f, 1f),
                color = if (exceeded) scheme.error else category.color(),
            )
        }
    }
}

@Composable
private fun GroupDetail(
    category: AppCategory,
    info: GroupInfo,
    appCount: Int,
    defaultLimitMin: Int,
    onLimit: (Int?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val limitMin = info.limitMin
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(category.color())
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(category.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "$appCount uygulama · bugün ${Time.format(info.countedMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Grubun günlük toplam limiti", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))

        val options = listOf("Limit yok", "Toplam limit")
        val selected = if (limitMin == null) 0 else 1
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selected == index,
                    onClick = { onLimit(if (index == 0) null else limitMin ?: 60) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (limitMin == null) {
            Text(
                "Bu gruptaki her uygulamaya ayrı ayrı varsayılan limit ($defaultLimitMin dk) uygulanıyor.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            MinuteStepper(
                minutes = limitMin,
                onMinus = { onLimit((limitMin - LimitStore.STEP).coerceAtLeast(LimitStore.MIN_LIMIT)) },
                onPlus = { onLimit((limitMin + LimitStore.STEP).coerceAtMost(LimitStore.MAX_LIMIT)) },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 45, 60, 90, 120, 180).forEach { preset ->
                    FilterChip(
                        selected = limitMin == preset,
                        onClick = { onLimit(preset) },
                        label = { Text("$preset dk") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Gruptaki uygulamaların toplamı bu süreyi geçince hepsi için uyarı çıkar. " +
                    "Özel limit verdiğin uygulamalar ayrıca kendi limitine de uyar; sınırsız yaptıkların sayılmaz.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupFooter(hidden: Int, expanded: Boolean, canCollapse: Boolean, onToggle: () -> Unit) {
    val shape: Shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    val showToggle = hidden > 0 || (expanded && canCollapse)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (showToggle) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (showToggle) 12.dp else 6.dp),
    ) {
        if (showToggle) {
            Text(
                if (expanded) "Daha az göster" else "$hidden uygulama daha",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val limitMs = row.limitMs
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.pkg, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.dailyAvgMs > 0 || row.avgSessionMs > 0) {
                    "ort. ${Time.format(row.dailyAvgMs)}/gün · ${Time.format(row.avgSessionMs)}/açılış"
                } else {
                    "Bu hafta kullanılmadı"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (limitMs != null && row.todayMs > 0) {
                Spacer(Modifier.height(6.dp))
                ProgressTrack(
                    fraction = (row.todayMs.toFloat() / limitMs).coerceIn(0f, 1f),
                    color = if (row.exceeded) scheme.error else scheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Time.format(row.todayMs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (row.exceeded) scheme.error else scheme.onSurface,
            )
            Text(
                when {
                    row.limitMin != null -> "/ ${row.limitMin} dk"
                    row.groupLimited -> "grup limiti"
                    else -> "sınırsız"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(color)
        )
    }
}

private object IconCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun peek(pkg: String): ImageBitmap? = cache[pkg]

    fun load(context: Context, pkg: String): ImageBitmap? = cache[pkg] ?: try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            .also { cache[pkg] = it }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun AppIcon(pkg: String, size: Dp) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState(IconCache.peek(pkg), pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, pkg) }
        }
    }
    val current = bitmap
    if (current != null) {
        Image(bitmap = current, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}

@Composable
private fun AppDetail(
    row: AppRow,
    labels: List<String>,
    defaultLimitMin: Int,
    onRule: (Int?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Bugün", Time.format(row.todayMs), Modifier.weight(1f), highlight = row.exceeded)
            StatTile("Günlük ortalama", Time.format(row.dailyAvgMs), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Açılış başına", Time.format(row.avgSessionMs), Modifier.weight(1f))
            StatTile("Bugünkü açılış", row.sessionsToday.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))
        Text("Son 7 gün", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        WeekBars(row.week, labels, barHeight = 56.dp)

        Spacer(Modifier.height(24.dp))
        Text("Günlük limit", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))

        val mode = when {
            row.rule == null -> 0
            row.rule > 0 -> 1
            else -> 2
        }
        val options = listOf("Varsayılan", "Özel", "Sınırsız")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = mode == index,
                    onClick = {
                        when (index) {
                            0 -> onRule(null)
                            1 -> onRule(row.limitMin ?: defaultLimitMin)
                            else -> onRule(LimitStore.UNLIMITED)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(12.dp))
        when (mode) {
            0 -> Text(
                when {
                    row.groupLimited -> "Kendi limiti yok; \"${row.category.title}\" grubunun toplam limitine tabi."
                    row.limitMin == null -> "Temel uygulama olduğu için varsayılan olarak sınırsız."
                    else -> "Varsayılan limit uygulanıyor: $defaultLimitMin dk."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )

            1 -> {
                val minutes = row.limitMin ?: defaultLimitMin
                MinuteStepper(
                    minutes = minutes,
                    onMinus = { onRule((minutes - LimitStore.STEP).coerceAtLeast(LimitStore.MIN_LIMIT)) },
                    onPlus = { onRule((minutes + LimitStore.STEP).coerceAtMost(LimitStore.MAX_LIMIT)) },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10, 15, 30, 45, 60, 90, 120).forEach { preset ->
                        FilterChip(
                            selected = minutes == preset,
                            onClick = { onRule(preset) },
                            label = { Text("$preset dk") },
                        )
                    }
                }
            }

            else -> Text(
                "Bu uygulama için limit yok, uyarı çıkmaz.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) scheme.error else scheme.onSurface,
        )
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
