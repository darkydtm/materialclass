package org.materialclass

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherTest {
	@Test
	fun displaysAppName() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val activity = instrumentation.startActivitySync(intent)
		try {
			val expected = context.getString(R.string.app_name)
			val deadline = android.os.SystemClock.uptimeMillis() + 10000
			var displayed = false
			while (!displayed && android.os.SystemClock.uptimeMillis() < deadline) {
				val root = instrumentation.uiAutomation.rootInActiveWindow
				displayed = root?.findAccessibilityNodeInfosByText(expected)
					?.any { it.text?.toString() == expected && it.isVisibleToUser } == true
				if (!displayed) android.os.SystemClock.sleep(100)
			}
			assertTrue("App name must be visible in the launcher", displayed)
		} finally {
			activity.finish()
		}
	}

	@Test
	fun launchesActivity() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
		assertFalse(activity.isFinishing)
		activity.finish()
	}
}
