package org.materialclass

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
		setContent { MaterialClassApp() }
	}
}
