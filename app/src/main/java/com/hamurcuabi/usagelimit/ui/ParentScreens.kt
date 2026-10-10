@file:OptIn(ExperimentalMaterial3Api::class)

package com.hamurcuabi.usagelimit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamurcuabi.usagelimit.cloud.Child
import com.hamurcuabi.usagelimit.cloud.ChildEvent
import com.hamurcuabi.usagelimit.cloud.ChildSnapshot
import com.hamurcuabi.usagelimit.cloud.ChildSync
import com.hamurcuabi.usagelimit.cloud.ParentCloud
import com.hamurcuabi.usagelimit.cloud.PushService
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.RuleSet
import com.hamurcuabi.usagelimit.data.Time
import com.hamurcuabi.usagelimit.data.UsageCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L

/** Ebeveyn tarafının kökü: giriş -> çocuk listesi -> çocuk detayı. */
@Composable
fun ParentRoot() {
    var signedIn by remember { mutableStateOf(ParentCloud.uid != null) }
    var openChildId by rememberSaveable { mutableStateOf<String?>(null) }

    if (!signedIn) {
        ParentAuthScreen(onSignedIn = { signedIn = true })
    } else {
        val children by remember { ParentCloud.children() }.collectAsStateWithLifecycle(initialValue = null)
        val open = children?.firstOrNull { it.id == openChildId }
        if (open != null) {
            ParentChildScreen(child = open, onBack = { openChildId = null })
        } else {
            ParentHomeScreen(
                children = children,
                onOpen = { openChildId = it.id },
                onSignOut = {
                    ParentCloud.signOut()
                    signedIn = false
                },
            )
        }
    }
}

@Composable
private fun ParentAuthScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configured = remember { ParentCloud.googleSignInConfigured(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Çocuklarım",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Çocuğunun telefonundaki kullanımı görmek ve limit koymak için Google hesabınla gir. Kayıtlar hesabına bağlanır; başka telefondan aynı hesapla girince hepsini görürsün.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!configured) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Google girişi bu sürümde henüz yapılandırılmamış.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            if (ParentCloud.signInWithGoogle(context)) onSignedIn()
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Giriş başarısız."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Google ile giriş yap", modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun ParentHomeScreen(children: List<Child>?, onOpen: (Child) -> Unit, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        PushService.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ParentCloud.registerPushToken()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ParentCloud.email ?: "Ebeveyn",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Çocuklarım",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = onSignOut) { Text("Çıkış") }
                }
            }

            when {
                children == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                children.isEmpty() -> item(key = "empty") {
                    Text(
                        "Henüz çocuk eklemedin. Ekleyince bir eşleştirme kodu çıkar; o kodu çocuğun telefonuna girersin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }

                else -> items(children, key = { it.id }) { child ->
                    ChildCard(child) { if (child.paired) onOpen(child) }
                }
            }

            item(key = "add") {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Çocuk ekle", modifier = Modifier.padding(vertical = 6.dp))
                }
                if (error != null) {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Çocuk ekle") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Adı") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val chosen = name
                        adding = false
                        error = null
                        scope.launch {
                            try {
                                ParentCloud.addChild(chosen)
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Çocuk eklenemedi."
                            }
                        }
                    },
                ) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun ChildCard(child: Child, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = child.paired, onClick = onClick),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(child.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (!child.paired) {
                Spacer(Modifier.height(8.dp))
                Text("Eşleştirme kodu", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Text(
                    child.pairCode.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 4.sp),
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
                Text(
                    "Çocuğun telefonunda uygulamayı aç, \"Çocuğun telefonu\"nu seç ve bu kodu gir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bugün ${Time.format(child.totalTodayMs)} · son veri ${ago(child.lastSeen)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                val warning = childWarning(child)
                if (warning != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
            }
        }
    }
}

/** Çocuğun telefonunda izlemenin çalışmadığına işaret eden durumlar. */
private fun childWarning(child: Child): String? {
    val missing = buildList {
        if (child.status["usage"] == false) add("kullanım erişimi")
        if (child.status["overlay"] == false) add("üstte gösterme")
        if (child.status["notify"] == false) add("bildirim")
        if (child.status["battery"] == false) add("pil muafiyeti")
    }
    val stale = child.lastSeen > 0 && System.currentTimeMillis() - child.lastSeen > STALE_AFTER_MS
    return when {
        stale -> "Uzun süredir veri gelmiyor. Telefon kapalı ya da çevrimdışı olabilir; uygulama durdurulmuş veya silinmiş de olabilir."
        missing.isNotEmpty() -> "Çocuğun telefonunda kapalı izinler: ${missing.joinToString(", ")}."
        else -> null
    }
}

private fun ago(time: Long): String {
    if (time <= 0) return "henüz yok"
    val minutes = (System.currentTimeMillis() - time) / Time.MINUTE_MS
    return when {
        minutes < 2 -> "az önce"
        minutes < 60 -> "$minutes dk önce"
        minutes < 60 * 24 -> "${minutes / 60} sa önce"
        else -> "${minutes / (60 * 24)} gün önce"
    }
}

@Composable
private fun ParentChildScreen(child: Child, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snapshot by remember(child.id) { ParentCloud.snapshot(child.id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val rules by remember(child.id) { ParentCloud.rules(child.id) }
        .collectAsStateWithLifecycle(initialValue = RuleSet())
    val events by remember(child.id) { ParentCloud.events(child.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val currentRules by rememberUpdatedState(rules)
    var showAllEvents by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    val data = snapshot
    val state = remember(data, rules) {
        UiState(
            loading = data == null,
            hasUsageAccess = true,
            canOverlay = true,
            canNotify = true,
            batteryOk = true,
            monitoring = true,
            calendar = data?.calendar ?: emptyMap(),
            dayDetails = data?.dayDetails ?: emptyMap(),
        ).withData(
            apps = data?.apps ?: emptyList(),
            rules = rules,
            weekLabels = data?.weekLabels?.takeIf { it.isNotEmpty() } ?: UsageCollector.dayLabels(),
        )
    }

    val actions = remember(child.id) {
        DashboardActions(
            changeDefaultLimit = { delta ->
                val next = (currentRules.defaultLimitMin + delta).coerceIn(LimitStore.MIN_LIMIT, LimitStore.MAX_LIMIT)
                ParentCloud.saveRules(child.id, currentRules.copy(defaultLimitMin = next))
            },
            changeMaxExtensions = { delta ->
                val next = (currentRules.maxExtensions + delta).coerceIn(0, LimitStore.MAX_EXTENSIONS_CAP)
                ParentCloud.saveRules(child.id, currentRules.copy(maxExtensions = next))
            },
            setRule = { pkg, rule -> ParentCloud.saveRules(child.id, currentRules.withRule(pkg, rule)) },
            setGroupLimit = { category, minutes ->
                ParentCloud.saveRules(child.id, currentRules.withGroupLimit(category.name, minutes))
            },
        )
    }

    Dashboard(
        state = state,
        actions = actions,
        mode = DashboardMode.REMOTE,
        title = child.name,
        onBack = onBack,
        extraTop = {
            item(key = "child-status") {
                Spacer(Modifier.height(12.dp))
                ChildStatusCard(child, data)
            }
            item(key = "child-events") {
                Spacer(Modifier.height(12.dp))
                EventsCard(events, showAllEvents) { showAllEvents = !showAllEvents }
            }
        },
        extraBottom = {
            item(key = "child-manage") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { confirmReset = true }) { Text("Telefonu yeniden eşleştir") }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Çocuğu sil", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Yeniden eşleştir") },
            text = { Text("Şu anki telefonun bağlantısı kesilir ve yeni bir kod üretilir. Koyduğun limitler korunur.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        try {
                            ParentCloud.resetPairing(child)
                        } catch (_: Exception) {
                        }
                        onBack()
                    }
                }) { Text("Yeni kod üret") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Vazgeç") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${child.name} silinsin mi?") },
            text = { Text("Kayıt, limitler ve kullanım verisi silinir. Bu geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            ParentCloud.deleteChild(child)
                        } catch (_: Exception) {
                        }
                        onBack()
                    }
                }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun ChildStatusCard(child: Child, snapshot: ChildSnapshot?) {
    val scheme = MaterialTheme.colorScheme
    val warning = childWarning(child)
    val today = Time.dayKey()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (warning != null) scheme.primaryContainer else scheme.surfaceContainer,
        contentColor = if (warning != null) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                if (child.deviceModel.isNotBlank()) child.deviceModel else "Çocuğun telefonu",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    snapshot == null || snapshot.updatedAt == 0L -> "Henüz veri gelmedi. Çocuğun telefonunda izinler verilince birkaç dakika içinde dolar."
                    snapshot.day != today -> "Bugün veri gelmedi; görünenler son gelen güne ait (${ago(snapshot.updatedAt)})."
                    else -> "Son güncelleme ${ago(snapshot.updatedAt)}. Veriler yaklaşık 5 dakikada bir yenilenir."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (warning != null) {
                Spacer(Modifier.height(6.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun EventsCard(events: List<ChildEvent>, showAll: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val formatter = remember { SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("tr")) }
    val visible = if (showAll) events else events.take(4)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                "Olaylar",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (events.isEmpty()) {
                Text(
                    "Henüz olay yok. Limit dolduğunda ya da ek süre alındığında burada görünür.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            visible.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = scheme.outlineVariant)
                val subject = if (event.scope == ChildSync.SCOPE_GROUP && event.groupTitle.isNotBlank()) {
                    "${event.label} (${event.groupTitle} grubu)"
                } else {
                    event.label
                }
                val extension = event.type == ChildSync.EVENT_EXTENSION
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        if (extension) "$subject: ${event.extensionMin} dk ek süre aldı" else "$subject: limit doldu",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (extension) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (extension) scheme.error else scheme.onSurface,
                    )
                    Text(
                        "${formatter.format(Date(event.at))} · ${Time.format(event.usedMs)} kullanmıştı, limit ${event.limitMin} dk",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (events.size > 4) {
                TextButton(onClick = onToggle, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showAll) "Daha az göster" else "Tümünü göster (${events.size})")
                }
            }
        }
    }
}
