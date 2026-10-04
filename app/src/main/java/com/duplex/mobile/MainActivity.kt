package com.duplex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.duplex.mobile.browser.TabManager
import com.duplex.mobile.ui.BrowserScreen

class MainActivity : ComponentActivity() {
    private var tabManager: TabManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val tabs = remember {
                TabManager(context).also { tabManager = it }
            }
            val settings = remember { SettingsStore(context) }
            val dark = when (settings.theme) {
                SettingsStore.THEME_LIGHT -> false
                SettingsStore.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                BrowserScreen(tabs, settings)
            }
        }
    }

    override fun onDestroy() {
        tabManager?.destroyAll()
        super.onDestroy()
    }
}
