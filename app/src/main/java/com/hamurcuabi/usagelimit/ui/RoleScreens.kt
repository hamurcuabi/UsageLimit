package com.hamurcuabi.usagelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamurcuabi.usagelimit.cloud.ChildPairing
import com.hamurcuabi.usagelimit.data.Role
import kotlinx.coroutines.launch

/** İlk açılış: uygulamanın bu telefonda nasıl kullanılacağını seçtirir. */
@Composable
fun RoleScreen(cloudAvailable: Boolean, onPick: (Role) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("⏳", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Bu telefonu kim kullanacak?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(20.dp))

            RoleOption(
                title = "Kendim için",
                description = "Kendi kullanımını gör, limitlerini kendin koy.",
                enabled = true,
                onClick = { onPick(Role.LOCAL) },
            )
            Spacer(Modifier.height(12.dp))
            RoleOption(
                title = "Ebeveynim",
                description = "Çocuğunun telefonundaki uygulamaları gör, limitleri buradan koy, ek süre aldığında haberin olsun.",
                enabled = cloudAvailable,
                onClick = { onPick(Role.PARENT) },
            )
            Spacer(Modifier.height(12.dp))
            RoleOption(
                title = "Çocuğun telefonu",
                description = "Ebeveynin verdiği kodla eşleşir. Limitler ebeveynden gelir, bu telefondan değiştirilemez.",
                enabled = cloudAvailable,
                onClick = { onPick(Role.CHILD) },
            )

            if (!cloudAvailable) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Ebeveyn ve çocuk kullanımı için Firebase yapılandırması gerekiyor; bu sürüme henüz eklenmemiş.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoleOption(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Çocuğun telefonunda: ebeveynin ekranındaki 6 haneli kodla eşleşme. */
@Composable
fun PairScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = !busy, onBack = onBack)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Eşleştirme kodu",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ebeveyn telefonunda çocuk eklendiğinde görünen 6 haneli kodu gir.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    textAlign = TextAlign.Center,
                    letterSpacing = 8.sp,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            ChildPairing.pair(context.applicationContext, code)
                            onPaired()
                        } catch (e: Exception) {
                            error = e.message ?: "Eşleşme başarısız. İnternet bağlantını kontrol et."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = code.length == 6 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Eşleştir", modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            TextButton(onClick = onBack, enabled = !busy) { Text("Geri") }
        }
    }
}
