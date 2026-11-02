package org.materialclass

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherTest {
	@Test
	fun launchesActivity() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
		assertFalse(activity.isFinishing)
		activity.finish()
	}
}
