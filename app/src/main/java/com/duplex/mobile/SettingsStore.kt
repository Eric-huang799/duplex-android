package com.duplex.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 全局外观设置（主题）。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("duplex_settings", Context.MODE_PRIVATE)

    var theme by mutableStateOf(prefs.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM)
        private set

    fun updateTheme(value: String) {
        theme = value
        prefs.edit().putString("theme", value).apply()
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val THEME_OPTIONS = listOf(
            THEME_SYSTEM to "跟随系统",
            THEME_LIGHT to "浅色",
            THEME_DARK to "深色"
        )
    }
}
