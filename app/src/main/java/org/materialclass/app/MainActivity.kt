package org.materialclass.app

import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.materialclass.ui.MaterialClassApp
import org.materialclass.ui.AppPreferences
import org.materialclass.api.GatewayLoginResult

class MainActivity : ComponentActivity() {
	private var touchX = 0f
	private var touchY = 0f
	private var clickableOnDown = false

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_DOWN) {
			touchX = event.rawX
			touchY = event.rawY
			clickableOnDown = hasClickableAt(window.decorView, touchX.toInt(), touchY.toInt())
		}
		if (event.actionMasked == MotionEvent.ACTION_UP && clickableOnDown) {
			val slop = ViewConfiguration.get(this).scaledTouchSlop
			if (kotlin.math.abs(event.rawX - touchX) < slop && kotlin.math.abs(event.rawY - touchY) < slop) {
				val settings = AppPreferences(this)
				if (settings.vibrationEnabled()) vibrateClick(settings.vibrationStrength())
			}
		}
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clickableOnDown = false
		return super.dispatchTouchEvent(event)
	}

	private fun vibrateClick(strength: Int) {
		val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
		if (!vibrator.hasVibrator()) return
		if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(10, strength))
		else @Suppress("DEPRECATION") vibrator.vibrate(10)
	}

	private fun hasClickableAt(view: View, x: Int, y: Int): Boolean {
		if (!view.isShown) return false
		val provider = view.accessibilityNodeProvider
		if (provider != null && hasClickableNode(provider.createAccessibilityNodeInfo(View.NO_ID), x, y)) return true
		if (view is ViewGroup) {
			for (index in 0 until view.childCount) if (hasClickableAt(view.getChildAt(index), x, y)) return true
		}
		val bounds = Rect()
		view.getGlobalVisibleRect(bounds)
		return view.isEnabled && view.isClickable && bounds.contains(x, y)
	}

	private fun hasClickableNode(node: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
		if (node == null) return false
		try {
			val bounds = Rect()
			node.getBoundsInScreen(bounds)
			if (!bounds.contains(x, y) || !node.isVisibleToUser) return false
			for (index in 0 until node.childCount) if (hasClickableNode(node.getChild(index), x, y)) return true
			return node.isEnabled && node.isClickable
		} finally {
			@Suppress("DEPRECATION")
			node.recycle()
		}
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
