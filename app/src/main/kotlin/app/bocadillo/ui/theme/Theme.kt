package app.bocadillo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * Classic dark look of the reference screens (black menus, dark bars, white text, blue sliders).
 * All colours of the app live here so every screen stays uniform.
 */
object Classic {
    val Background = Color(0xFF000000)
    val Menu = Color(0xFF111111)
    val Bar = Color(0xFF1F1F1F)
    val BarTop = Color(0xFF2A2A2A)
    val Section = Color(0xFF2B2B2B)
    val SectionText = Color(0xFFB8B8B8)
    val Divider = Color(0xFF2E2E2E)
    val Cell = Color(0xFF3A3A3A)
    val ButtonFill = Color(0xFF262626)
    val ButtonBorder = Color(0xFF7A7A7A)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFBDBDBD)
    val Accent = Color(0xFF2196F3)
    val AccentDark = Color(0xFF0D47A1)
    val Selected = Color(0xFF1565C0)
}

private val Scheme = darkColorScheme(
    primary = Classic.Accent, onPrimary = Color.White,
    primaryContainer = Classic.Selected, onPrimaryContainer = Color.White,
    secondary = Classic.Accent, onSecondary = Color.White,
    secondaryContainer = Classic.Cell, onSecondaryContainer = Color.White,
    background = Classic.Background, onBackground = Classic.Text,
    surface = Classic.Menu, onSurface = Classic.Text,
    surfaceVariant = Classic.Cell, onSurfaceVariant = Classic.TextDim,
    surfaceContainer = Classic.Bar, surfaceContainerHigh = Classic.Bar, surfaceContainerLow = Classic.Menu,
    surfaceContainerHighest = Classic.Cell, surfaceContainerLowest = Classic.Background,
    outline = Classic.ButtonBorder, outlineVariant = Classic.Divider,
)

/** The app always uses the classic dark scheme (no dynamic colours), as in the reference. */
@Composable
fun BocadilloTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
    )
    MaterialTheme(colorScheme = Scheme, typography = typography, content = content)
}
