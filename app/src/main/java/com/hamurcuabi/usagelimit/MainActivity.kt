package com.hamurcuabi.usagelimit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hamurcuabi.usagelimit.ui.MainScreen
import com.hamurcuabi.usagelimit.ui.UsageLimitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UsageLimitTheme {
                MainScreen()
            }
        }
    }
}
