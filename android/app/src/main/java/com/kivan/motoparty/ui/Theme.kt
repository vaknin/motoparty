package com.kivan.motoparty.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Always dark: the phone lives in a tank bag or a pocket, and is read in a glance. */
object Palette {
    /** The same pairs as the iPhone's buttons, so both phones read alike. */
    val Talk = Color(0xFFFF7A2F)
    val TalkOpen = Color(0xFFE5484D)
    val Good = Color(0xFF4ADE80)
    val Waiting = Color(0xFFFBBF24)
    val Off = Color(0xFF6B7280)

    /** Text and icons on [Talk]: about 7:1, where white was 2.6:1 (UA1). */
    val OnTalk = Color(0xFF1F1004)
    val OnTalkOpen = Color.White
}

/** Digits of equal width, so a running time does not jitter. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

private val scheme = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF1F1004),
    primaryContainer = Color(0xFF4A2A12),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFF8AB4FF),
    onSecondary = Color(0xFF0B1B33),
    secondaryContainer = Color(0xFF2E333C),
    onSecondaryContainer = Color(0xFFE7E9EC),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE7E9EC),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = Color(0xFF22262D),
    // About 10:1 on the background: secondary text has to be readable in sunlight (was #9BA1AA).
    onSurfaceVariant = Color(0xFFB8BEC7),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF14171B),
    surfaceContainer = Color(0xFF181B20),
    surfaceContainerHigh = Color(0xFF1F2329),
    surfaceContainerHighest = Color(0xFF282C33),
    outline = Color(0xFF6B7380),
    outlineVariant = Color(0xFF2A2F36),
    error = Color(0xFFFF6B6B),
    inverseSurface = Color(0xFFE7E9EC),
    inverseOnSurface = Color(0xFF14171B),
    inversePrimary = Color(0xFFB34A00),
)

@Composable
fun MotopartyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
