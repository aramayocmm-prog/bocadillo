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
    // measured on the reference screenshots (747×1600, menus and sticker tools)
    val Background = Color(0xFF000000)
    val Menu = Color(0xFF111111)          // list background
    val Bar = Color(0xFF1E1E1E)           // top bar
    val BarTop = Color(0xFF1E1E1E)
    val Section = Color(0xFF232323)       // group header ("Transform Picture", "Misc"…)
    val SectionText = Color(0xFF9E9E9E)
    val Divider = Color(0xFF1A1A1A)
    val Cell = Color(0xFF3A3A3A)
    val ButtonFill = Color(0xFF262626)
    val ButtonBorder = Color(0xFF7A7A7A)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFBDBDBD)
    val Accent = Color(0xFF2196F3)
    val AccentDark = Color(0xFF0D47A1)
    val Selected = Color(0xFF1565C0)
    val Confirm = Color(0xFF6CC644)       // green check mark of the sticker tools
    // home screen
    val Home = Color(0xFF333333)
    val HomeButton = Color(0xFFCFCFCF)
    val HomeButtonText = Color(0xFF333333)
    // light sticker tools (New Title / New Word Balloon / Font / shape picker)
    val LightPreview = Color(0xFFCFCFCF)
    val LightGrid = Color(0xFFDFDFDF)
    val LightField = Color(0xFFFFFFFF)
    val LightFieldBorder = Color(0xFF7B9EE0)
    val LightHeader = Color(0xFFCCCCCC)
    val LightHeaderText = Color(0xFF333333)
    val LightRow = Color(0xFFFFFFFF)
    val LightDivider = Color(0xFFE6E6E6)
    val LightMore = Color(0xFFA9D3EB)
    val ShapeTop = Color(0xFFD0D0D0)
    val ShapeBottom = Color(0xFFB5B3B6)
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
