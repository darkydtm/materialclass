package org.materialclass.app

import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.materialclass.ui.MaterialClassApp
import org.materialclass.ui.AppPreferences
import org.materialclass.api.GatewayLoginResult

class MainActivity : ComponentActivity() {
	private fun vibrateClick(strength: Int) {
		val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
		if (!vibrator.hasVibrator()) return
		if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(20, strength.coerceIn(1, 255)))
		else @Suppress("DEPRECATION") vibrator.vibrate(20)
	}


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val runtime = (application as MaterialClassApplication).runtime
		setContent {
			MaterialClassApp(
				onClickFeedback = {
					val settings = AppPreferences(this)
					if (settings.vibrationEnabled()) vibrateClick(settings.vibrationStrength())
				},
				repository = runtime.repository,
				loginHandler = { credentials ->
					runtime.gateway.loginForUi(credentials).also { if (it is GatewayLoginResult.Success) runtime.updateBackgroundSchedule() }
				},
				onBackgroundSettingsChanged = runtime::updateBackgroundSchedule,
				onNotificationSettingsChange = runtime::changeNotificationSettings,
				onSimulateNotification = runtime::simulateNotification,
				startAuthenticated = runtime.startAuthenticated,
			)
		}
	}
}
