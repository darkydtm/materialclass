package org.materialclass.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.materialclass.ui.AppPreferences
import org.materialclass.ui.ThemeMode

@Composable
fun MaterialClassRoot(preferences: AppPreferences, content: @Composable () -> Unit) {
	var theme by remember { mutableStateOf(preferences.theme()) }
	MaterialClassTheme(theme, content)
}
