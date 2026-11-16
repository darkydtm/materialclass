package org.materialclass.app

import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.materialclass.ui.MaterialClassApp
import org.materialclass.ui.AppPreferences
import org.materialclass.api.GatewayLoginResult

class MainActivity : ComponentActivity() {
	private val tapTracker by lazy { TapGestureTracker(ViewConfiguration.get(this).scaledTouchSlop.toFloat()) }

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		val handled = super.dispatchTouchEvent(event)
		val isTap = when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				tapTracker.onDown(event.x, event.y)
				false
			}
			MotionEvent.ACTION_MOVE -> {
				tapTracker.onMove(event.x, event.y)
				false
			}
			MotionEvent.ACTION_UP -> tapTracker.onUp(event.x, event.y, handled)
			MotionEvent.ACTION_CANCEL -> {
				tapTracker.onCancel()
				false
			}
			else -> false
		}
		if (isTap) {
			val settings = AppPreferences(this)
			if (settings.vibrationEnabled()) vibrateClick(settings.vibrationStrength())
		}
		return handled
	}

	private fun vibrateClick(strength: Int) {
		val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
		if (!vibrator.hasVibrator()) return
		if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(10, strength))
		else @Suppress("DEPRECATION") vibrator.vibrate(10)
	}


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val runtime = (application as MaterialClassApplication).runtime
		setContent {
			MaterialClassApp(
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
