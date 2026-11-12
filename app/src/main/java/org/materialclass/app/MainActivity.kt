package org.materialclass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			LauncherTheme {
				Text(text = launcherMessage, fontSize = 32.sp)
			}
		}
	}
}

@Composable
private fun LauncherTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val darkTheme = isSystemInDarkTheme()
	val colorScheme = when {
		android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && darkTheme ->
			dynamicDarkColorScheme(context)
		android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
			dynamicLightColorScheme(context)
		darkTheme -> darkColorScheme()
		else -> expressiveLightColorScheme()
	}

	MaterialExpressiveTheme(
		colorScheme = colorScheme,
		motionScheme = MotionScheme.expressive(),
		content = content,
	)
}
