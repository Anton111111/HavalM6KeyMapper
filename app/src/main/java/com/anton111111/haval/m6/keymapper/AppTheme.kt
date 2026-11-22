package com.anton111111.haval.m6.keymapper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HavalColorSuccess = Color(0xFF00C896)
val HavalColorWarning = Color(0xFFFFB74D)
val HavalColorAccentCyan = Color(0xFF00BCD4)
val HavalColorAccentSoft = Color(0xFF80CBC4)

private val HavalColorScheme = darkColorScheme(
    primary = Color(0xFF37474F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF607D8B),
    onSecondary = Color(0xFFE1E8EC),
    tertiary = Color(0xFF008FA8),
    onTertiary = Color(0xFFE1E8EC),
    background = Color(0xFF0B1114),
    onBackground = Color(0xFFE1E8EC),
    surface = Color(0xFF12181C),
    onSurface = Color(0xFFE1E8EC),
    surfaceVariant = Color(0xFF29343A),
    onSurfaceVariant = Color(0xFFAAB7C0),
    outline = Color(0xFF71818A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun HavalM6Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HavalColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
