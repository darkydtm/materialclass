package org.materialclass

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import android.os.Bundle

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			MaterialExpressiveTheme {
				Text(getString(R.string.app_name))
			}
		}
	}
}
