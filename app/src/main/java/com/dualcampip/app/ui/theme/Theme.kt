package com.dualcampip.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF090A0C)
val Paper = Color(0xFFF3EFE6)
val Muted = Color(0xFFA7A39A)
val Line = Color(0xFF2E3138)
val RecordRed = Color(0xFFE23B2F)
val WarningBg = Color(0xFF2A2418)
val WarningFg = Color(0xFFF0D7A2)
val SavedFg = Color(0xFFC9E2D3)
val Chip = Color(0xFF17191D)

private val scheme = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Chip,
    onSurface = Paper,
    error = RecordRed,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Paper),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Paper),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Paper),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Muted),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Paper),
)

@Composable
fun DualCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        content = content,
    )
}
