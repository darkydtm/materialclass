package org.materialclass

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineUiTest {
	private fun contains(node: AccessibilityNodeInfo, text: String): Boolean {
		if (node.text?.toString() == text && node.isVisibleToUser) return true
		return (0 until node.childCount).any { node.getChild(it)?.let { child -> contains(child, text) } == true }
	}

	@Test
	fun firstEntryShowsWarningAndUnavailableBackendTogether() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().clear().commit()
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		try {
			val deadline = SystemClock.uptimeMillis() + 10000
			var observed = false
			while (!observed && SystemClock.uptimeMillis() < deadline) {
				val root = instrumentation.uiAutomation.rootInActiveWindow
				observed = root != null && contains(root, context.getString(R.string.warning_text)) && if (BuildConfig.INTEGRATION_TEST_NATIVE_UI) {
					!contains(root, context.getString(R.string.backend_unavailable)) && contains(root, context.getString(R.string.sign_in))
				} else contains(root, context.getString(R.string.backend_unavailable))
				if (!observed) SystemClock.sleep(100)
			}
			assertTrue("Warning and backend status must be visible without a consent gate", observed)
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}
}
