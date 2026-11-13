package org.materialclass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.materialclass.ui.ThemeMode

@Composable
fun MaterialClassTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
	val dark = when (themeMode) {
		ThemeMode.SYSTEM -> isSystemInDarkTheme()
		ThemeMode.LIGHT -> false
		ThemeMode.DARK, ThemeMode.BLACK -> true
	}
	val colorScheme = when (themeMode) {
		ThemeMode.BLACK -> darkColorScheme(
			background = Color.Black,
			surface = Color.Black,
			surfaceContainer = Color.Black,
			surfaceContainerLow = Color.Black,
			surfaceContainerHigh = Color(0xFF171717),
			onBackground = Color.White,
			onSurface = Color.White,
		)
		ThemeMode.LIGHT -> expressiveLightColorScheme()
		ThemeMode.DARK, ThemeMode.SYSTEM -> if (dark) darkColorScheme() else lightColorScheme()
	}
	val typography = androidx.compose.material3.Typography(
		displaySmall = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
		headlineMedium = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
		titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
	)
	MaterialExpressiveTheme(
		colorScheme = colorScheme,
		motionScheme = MotionScheme.expressive(),
		typography = typography,
		content = content,
	)
}
