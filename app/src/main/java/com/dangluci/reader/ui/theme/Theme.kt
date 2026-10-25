package com.dangluci.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dangluci.reader.data.Settings

private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    secondary = Color(0xFF8A6D3B),
    background = Color(0xFFFBF8F1),
    surface = Color(0xFFFBF8F1),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC1B0),
    secondary = Color(0xFFD9B97A),
    background = Color(0xFF141414),
    surface = Color(0xFF141414),
    onBackground = Color(0xFFCFCAC0),
    onSurface = Color(0xFFCFCAC0),
)

@Composable
fun ReaderTheme(theme: Settings.Theme = Settings.Theme.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Settings.Theme.SYSTEM -> isSystemInDarkTheme()
        Settings.Theme.LIGHT -> false
        Settings.Theme.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
