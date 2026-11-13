package bb.produce.invoicing.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import bb.produce.invoicing.R

/**
 * Colour tokens taken from the v4 prototype (reference/, "Produce Invoicing v4").
 * Fonts: Public Sans (OFL, bundled in res/font, licence in docs/licenses). v4's IBM Plex Mono
 * labels use the system monospace: no TTF build was obtainable from an allowed source.
 */
object PiColors {
    val Forest = Color(0xFF2D5E3E)
    val ForestDark = Color(0xFF234C31)
    val ForestDeep = Color(0xFF1F4A2E)
    val ForestTint = Color(0xFFE7EFE6)
    val Amber = Color(0xFF7A4F0E)
    val AmberTint = Color(0xFFF6EDDA)
    val Red = Color(0xFFA3352A)
    val Ink = Color(0xFF1C1F1A)
    val InkMuted = Color(0xFF5D6158)
    val Border = Color(0xFFDDD8CC)
    val BorderStrong = Color(0xFFC9C3B5)
    val Background = Color(0xFFF5F2EA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFE7E3D9)
}

private val LightColors = lightColorScheme(
    primary = PiColors.Forest,
    onPrimary = Color.White,
    primaryContainer = PiColors.ForestTint,
    onPrimaryContainer = PiColors.ForestDeep,
    secondary = PiColors.ForestDark,
    // Selected chips / navigation indicator: v4 green tint instead of Material's default lavender.
    secondaryContainer = PiColors.ForestTint,
    onSecondaryContainer = PiColors.ForestDeep,
    tertiary = PiColors.Amber,
    tertiaryContainer = PiColors.AmberTint,
    error = PiColors.Red,
    background = PiColors.Background,
    onBackground = PiColors.Ink,
    surface = PiColors.Surface,
    onSurface = PiColors.Ink,
    surfaceVariant = PiColors.SurfaceVariant,
    // Navigation bars, menus and sheets use the surface-container family: v4 warm neutrals, not lavender.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF8F3),
    surfaceContainer = Color(0xFFF1EEE6),
    surfaceContainerHigh = Color(0xFFEEEAE1),
    surfaceContainerHighest = PiColors.SurfaceVariant,
    onSurfaceVariant = PiColors.InkMuted,
    outline = PiColors.BorderStrong,
    outlineVariant = PiColors.Border,
)

private val PiShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
)

val PublicSans = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_medium, FontWeight.Medium),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
    Font(R.font.public_sans_bold, FontWeight.Bold),
)

/** Monospace used for small uppercase labels and the 58 mm receipt preview. */
val PiMono: FontFamily = FontFamily.Monospace

private val PiTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = PublicSans),
        displayMedium = t.displayMedium.copy(fontFamily = PublicSans),
        displaySmall = t.displaySmall.copy(fontFamily = PublicSans),
        headlineLarge = t.headlineLarge.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontFamily = PublicSans),
        bodyMedium = t.bodyMedium.copy(fontFamily = PublicSans),
        bodySmall = t.bodySmall.copy(fontFamily = PublicSans),
        labelLarge = t.labelLarge.copy(fontFamily = PublicSans, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontFamily = PublicSans),
        labelSmall = t.labelSmall.copy(fontFamily = PublicSans),
    )
}

@Composable
fun ProduceInvoicingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, shapes = PiShapes, typography = PiTypography, content = content)
}
