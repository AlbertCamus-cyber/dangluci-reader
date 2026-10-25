package com.dangluci.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 阅读设置：字号、主题。存在 SharedPreferences，本机有效，不同步。 */
class Settings(context: Context) {
    enum class Theme(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("夜间") }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(prefs.getFloat("font_size", 19f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _theme = MutableStateFlow(runCatching { Theme.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(Theme.SYSTEM))
    val theme: StateFlow<Theme> = _theme.asStateFlow()

    fun setFontSize(v: Float) { _fontSize.value = v; prefs.edit().putFloat("font_size", v).apply() }
    fun setTheme(t: Theme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }

    companion object { const val MIN_FONT = 14f; const val MAX_FONT = 28f }
}
