package com.hamurcuabi.usagelimit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hamurcuabi.usagelimit.cloud.Cloud
import com.hamurcuabi.usagelimit.data.Role
import com.hamurcuabi.usagelimit.data.RoleStore
import com.hamurcuabi.usagelimit.ui.DashboardMode
import com.hamurcuabi.usagelimit.ui.LocalDashboard
import com.hamurcuabi.usagelimit.ui.PairScreen
import com.hamurcuabi.usagelimit.ui.ParentRoot
import com.hamurcuabi.usagelimit.ui.RoleScreen
import com.hamurcuabi.usagelimit.ui.UsageLimitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UsageLimitTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { ROLE, MAIN, PAIR, CHILD }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val roles = remember { RoleStore(context) }
    val cloudAvailable = remember { Cloud.isAvailable(context) }

    var screen by remember {
        mutableStateOf(
            when (roles.role) {
                Role.LOCAL, Role.PARENT -> Screen.MAIN
                Role.CHILD -> if (roles.childId != null) Screen.CHILD else Screen.ROLE
                null -> Screen.ROLE
            }
        )
    }

    when (screen) {
        Screen.ROLE -> RoleScreen(cloudAvailable) { picked ->
            if (picked == Role.CHILD) {
                // Çocuk rolü ancak eşleşme tamamlanınca kaydedilir.
                screen = Screen.PAIR
            } else {
                roles.role = Role.LOCAL
                screen = Screen.MAIN
            }
        }

        Screen.MAIN -> MainTabs(
            roles = roles,
            cloudAvailable = cloudAvailable,
            onChangeRole = {
                roles.role = null
                screen = Screen.ROLE
            },
        )

        Screen.PAIR -> PairScreen(
            onPaired = { screen = Screen.CHILD },
            onBack = { screen = Screen.ROLE },
        )

        // Çocuk modundan çıkış yok: limitler yalnızca ebeveyn tarafından değiştirilebilir.
        Screen.CHILD -> LocalDashboard(
            mode = DashboardMode.CHILD,
            title = roles.childName?.let { "Kullanım Limiti · $it" } ?: "Kullanım Limiti",
        )
    }
}

/**
 * Kendi kullanımın ve çocukların aynı ekranda, alt sekmelerle.
 * Sekmeler arasında geçerken giriş ya da rol seçimi tekrarlanmaz.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainTabs(roles: RoleStore, cloudAvailable: Boolean, onChangeRole: () -> Unit) {
    // Son açık sekme hatırlanır (PARENT = çocuklar sekmesi).
    var parentTab by remember { mutableStateOf(cloudAvailable && roles.role == Role.PARENT) }
    val stateHolder = rememberSaveableStateHolder()

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Alt gezinme çubuğu boşluğunu sekme çubuğu üstlenir; içerik ikinci kez boşluk bırakmasın.
                .consumeWindowInsets(if (cloudAvailable) WindowInsets.navigationBars else WindowInsets(0, 0, 0, 0))
        ) {
            if (parentTab) {
                stateHolder.SaveableStateProvider("parent") { ParentRoot() }
            } else {
                stateHolder.SaveableStateProvider("mine") {
                    LocalDashboard(
                        mode = DashboardMode.LOCAL,
                        extraBottom = {
                            item(key = "change-role") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = onChangeRole) { Text("Bu telefonu çocuk telefonu yap") }
                                }
                            }
                        },
                    )
                }
            }
        }

        if (cloudAvailable) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = !parentTab,
                    onClick = {
                        parentTab = false
                        roles.role = Role.LOCAL
                    },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Kullanımım") },
                )
                NavigationBarItem(
                    selected = parentTab,
                    onClick = {
                        parentTab = true
                        roles.role = Role.PARENT
                    },
                    icon = { Icon(Icons.Default.Face, contentDescription = null) },
                    label = { Text("Çocuklarım") },
                )
            }
        }
    }
}
