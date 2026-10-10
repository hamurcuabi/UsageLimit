package com.hamurcuabi.usagelimit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private enum class Screen { ROLE, LOCAL, PARENT, PAIR, CHILD }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val roles = remember { RoleStore(context) }
    val cloudAvailable = remember { Cloud.isAvailable(context) }

    var screen by remember {
        mutableStateOf(
            when (roles.role) {
                Role.LOCAL -> Screen.LOCAL
                Role.PARENT -> if (cloudAvailable) Screen.PARENT else Screen.ROLE
                Role.CHILD -> if (roles.childId != null) Screen.CHILD else Screen.ROLE
                null -> Screen.ROLE
            }
        )
    }

    when (screen) {
        Screen.ROLE -> RoleScreen(cloudAvailable) { picked ->
            when (picked) {
                Role.LOCAL -> {
                    roles.role = Role.LOCAL
                    screen = Screen.LOCAL
                }
                Role.PARENT -> {
                    roles.role = Role.PARENT
                    screen = Screen.PARENT
                }
                // Çocuk rolü ancak eşleşme tamamlanınca kaydedilir.
                Role.CHILD -> screen = Screen.PAIR
            }
        }

        Screen.LOCAL -> LocalDashboard(
            mode = DashboardMode.LOCAL,
            extraBottom = {
                item(key = "change-role") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = {
                            roles.role = null
                            screen = Screen.ROLE
                        }) { Text("Kullanım şeklini değiştir") }
                    }
                }
            },
        )

        Screen.PARENT -> ParentRoot(onExit = {
            roles.role = null
            screen = Screen.ROLE
        })

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
