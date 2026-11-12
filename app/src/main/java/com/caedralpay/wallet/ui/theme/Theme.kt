package com.caedralpay.wallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LocalDark = staticCompositionLocalOf { true }
enum class ThemeMode(val label: String) { System("Sistema"), Light("Claro"), Dark("Escuro") }
class ThemeController internal constructor(initial: ThemeMode, private val save: (ThemeMode) -> Unit) {
    var mode by mutableStateOf(initial)
        private set
    fun select(value: ThemeMode) { mode = value; save(value) }
}
val LocalThemeController = staticCompositionLocalOf<ThemeController?> { null }

object CaedralColors {
    val Background: Color @Composable get() = if (LocalDark.current) Color(0xFF0B0B0B) else Color(0xFFFAF9F6)
    val Panel: Color @Composable get() = if (LocalDark.current) Color(0xFF151515) else Color.White
    val Selected: Color @Composable get() = if (LocalDark.current) Color(0xFF292824) else Color(0xFFECE8E1)
    val Brand: Color @Composable get() = if (LocalDark.current) Color(0xFFDDD6CD) else Color(0xFF635547)
    val Button: Color @Composable get() = Color(0xFFDDD6CD)
    val OnButton: Color @Composable get() = Color(0xFF211E19)
    val Text: Color @Composable get() = if (LocalDark.current) Color(0xFFF5F4F2) else Color(0xFF211E19)
    val Muted: Color @Composable get() = if (LocalDark.current) Color(0xFFAAA7A2) else Color(0xFF706A62)
    val Disabled: Color @Composable get() = if (LocalDark.current) Color(0xFF85867E) else Color(0xFF817B73)
    val Divider: Color @Composable get() = if (LocalDark.current) Color(0xFF292A26) else Color(0xFFDDD8CF)
    val Error: Color @Composable get() = if (LocalDark.current) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    val Positive: Color @Composable get() = if (LocalDark.current) Color(0xFF78DCA0) else Color(0xFF247044)
    val Negative: Color @Composable get() = if (LocalDark.current) Color(0xFFFF9690) else Color(0xFFB3261E)
}

@Composable
fun WalletTheme(mode: ThemeMode? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val controller = remember {
        val prefs = context.getSharedPreferences("appearance", 0)
        ThemeController(ThemeMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: ThemeMode.System) {
            prefs.edit().putString("mode", it.name).apply()
        }
    }
    val selected = mode ?: controller.mode
    val dark = when (selected) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    CompositionLocalProvider(LocalDark provides dark, LocalThemeController provides controller) {
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = CaedralColors.Brand, onPrimary = if (dark) CaedralColors.OnButton else Color.White,
        secondary = CaedralColors.Brand, background = CaedralColors.Background,
        onBackground = CaedralColors.Text, surface = CaedralColors.Panel,
        onSurface = CaedralColors.Text, surfaceVariant = CaedralColors.Selected,
        onSurfaceVariant = CaedralColors.Muted, outline = CaedralColors.Muted,
        error = CaedralColors.Error, surfaceContainer = CaedralColors.Panel,
        surfaceContainerHigh = CaedralColors.Selected, surfaceContainerHighest = CaedralColors.Selected
    )
    MaterialTheme(colorScheme = scheme, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))) {
        CompositionLocalProvider(LocalContentColor provides CaedralColors.Text) { content() }
    }
    }
}
