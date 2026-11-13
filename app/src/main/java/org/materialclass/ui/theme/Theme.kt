package org.materialclass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.materialclass.ui.ThemeMode

private val Satoshi = FontFamily(
	Font(org.materialclass.R.font.satoshi_regular, FontWeight.Normal),
	Font(org.materialclass.R.font.satoshi_medium, FontWeight.Medium),
	Font(org.materialclass.R.font.satoshi_bold, FontWeight.Bold),
)

@Composable
fun MaterialClassTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
	val dark = when (themeMode) {
		ThemeMode.SYSTEM -> isSystemInDarkTheme()
		ThemeMode.LIGHT -> false
		ThemeMode.DARK, ThemeMode.BLACK -> true
	}
	val effectiveMode = if (themeMode == ThemeMode.SYSTEM) {
		if (dark) ThemeMode.DARK else ThemeMode.LIGHT
	} else themeMode
	val colorScheme = when (effectiveMode) {
		ThemeMode.BLACK -> darkColorScheme(
			primary = Color(0xFFB5CCB6), onPrimary = Color(0xFF203526), primaryContainer = Color(0xFF263A2B), onPrimaryContainer = Color(0xFFD1E8D2),
			secondary = Color(0xFFBBCBB8), onSecondary = Color(0xFF263426), secondaryContainer = Color(0xFF2B382B), onSecondaryContainer = Color(0xFFD6E8D3),
			tertiary = Color(0xFFA1CBD5), onTertiary = Color(0xFF07363E), tertiaryContainer = Color(0xFF173E46), onTertiaryContainer = Color(0xFFBDEAF5),
			error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
			background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF090C09), surfaceContainer = Color(0xFF101410), surfaceContainerHigh = Color(0xFF1A1E1A), surfaceContainerHighest = Color(0xFF252925),
			onBackground = Color(0xFFE1E4DF), onSurface = Color(0xFFE1E4DF), onSurfaceVariant = Color(0xFFC2C9BF), outline = Color(0xFF8C938A), outlineVariant = Color(0xFF424940),
		)
		ThemeMode.LIGHT, ThemeMode.SYSTEM -> lightColorScheme(
			primary = Color(0xFF4F6354), onPrimary = Color.White, primaryContainer = Color(0xFFD1E8D2), onPrimaryContainer = Color(0xFF0C1F13),
			secondary = Color(0xFF535F52), onSecondary = Color.White, secondaryContainer = Color(0xFFD6E8D3), onSecondaryContainer = Color(0xFF111F14),
			tertiary = Color(0xFF39656F), onTertiary = Color.White, tertiaryContainer = Color(0xFFBDEAF5), onTertiaryContainer = Color(0xFF001F26),
			error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
			background = Color(0xFFF9FAF7), surface = Color(0xFFF9FAF7), surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F5F1), surfaceContainer = Color(0xFFEDF0EB), surfaceContainerHigh = Color(0xFFE7EAE5), surfaceContainerHighest = Color(0xFFE1E4DF),
			onBackground = Color(0xFF1A1C19), onSurface = Color(0xFF1A1C19), onSurfaceVariant = Color(0xFF424940), outline = Color(0xFF727970), outlineVariant = Color(0xFFC2C9BF),
		)
		ThemeMode.DARK -> darkColorScheme(
			primary = Color(0xFFB5CCB6), onPrimary = Color(0xFF203526), primaryContainer = Color(0xFF374B3B), onPrimaryContainer = Color(0xFFD1E8D2),
			secondary = Color(0xFFBBCBB8), onSecondary = Color(0xFF263426), secondaryContainer = Color(0xFF3C4A3B), onSecondaryContainer = Color(0xFFD6E8D3),
			tertiary = Color(0xFFA1CBD5), onTertiary = Color(0xFF07363E), tertiaryContainer = Color(0xFF204D55), onTertiaryContainer = Color(0xFFBDEAF5),
			error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
			background = Color(0xFF111411), surface = Color(0xFF111411), surfaceContainerLowest = Color(0xFF0C0F0C), surfaceContainerLow = Color(0xFF191D19), surfaceContainer = Color(0xFF1D211D), surfaceContainerHigh = Color(0xFF272B27), surfaceContainerHighest = Color(0xFF323632),
			onBackground = Color(0xFFE1E4DF), onSurface = Color(0xFFE1E4DF), onSurfaceVariant = Color(0xFFC2C9BF), outline = Color(0xFF8C938A), outlineVariant = Color(0xFF424940),
		)
	}
	val typography = androidx.compose.material3.Typography(
		displaySmall = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 48.sp, letterSpacing = (-0.07).em),
		headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.56.sp, letterSpacing = (-0.045).em),
		headlineMedium = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.32.sp, letterSpacing = (-0.03).em),
		headlineSmall = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 24.78.sp, letterSpacing = (-0.03).em),
		titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 23.sp, letterSpacing = (-0.02).em),
		titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.02).em),
		bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.2.sp),
		bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.3.sp),
		bodySmall = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.8.sp),
		labelLarge = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.6.sp),
		labelMedium = androidx.compose.ui.text.TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.8.sp),
	)
	MaterialExpressiveTheme(
		colorScheme = colorScheme,
		motionScheme = MotionScheme.standard(),
		typography = typography,
		content = content,
	)
}
