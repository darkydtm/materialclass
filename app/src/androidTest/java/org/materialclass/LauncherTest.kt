package org.materialclass

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherTest {
	@get:Rule
	val compose = createAndroidComposeRule<MainActivity>()

	@Test
	fun displaysAppName() {
		compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()
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
