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

internal object VibrationStrengthPolicy {
	fun isAdjustable(apiLevel: Int, hasVibrator: Boolean, hasAmplitudeControl: Boolean): Boolean =
		apiLevel >= 26 && hasVibrator && hasAmplitudeControl

	fun amplitude(apiLevel: Int, hasAmplitudeControl: Boolean, strength: Int): Int? = when {
		apiLevel < 26 -> null
		hasAmplitudeControl -> strength.coerceIn(1, 255)
		else -> VibrationEffect.DEFAULT_AMPLITUDE
	}
}

class MainActivity : ComponentActivity() {
	private fun vibrateClick(strength: Int) {
		val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
		if (!vibrator.hasVibrator()) return
		if (Build.VERSION.SDK_INT >= 26) {
			val amplitude = VibrationStrengthPolicy.amplitude(Build.VERSION.SDK_INT, vibrator.hasAmplitudeControl(), strength) ?: return
			vibrator.vibrate(VibrationEffect.createOneShot(20, amplitude))
		}
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
				cancellableLoginHandler = { credentials, cancellation ->
					runtime.gateway.loginForUi(credentials, cancellation).also {
						if (it is GatewayLoginResult.Success) runtime.updateBackgroundSchedule()
					}
				},
				onBackgroundSettingsChanged = runtime::updateBackgroundSchedule,
				onNotificationSettingsChange = runtime::changeNotificationSettings,
				onSimulateNotification = runtime::simulateNotification,
				startAuthenticated = runtime.startAuthenticated,
			)
		}
	}
}
