package org.materialclass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.cancel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VibrationFeedbackTest {
	@get:Rule
	val rule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun settingsRowOnlyProvidesFeedbackForCompletedClick() {
		val navigator = AppNavigator(AppRoute.Settings)
		var feedback = 0
		rule.setContent {
			CompositionLocalProvider(LocalClickFeedback provides { feedback++ }) {
				SettingsScreen(PaddingValues(), { it }, navigator, AppLanguage.RU, ThemeMode.SYSTEM, false, false, false)
			}
		}
		val row = rule.onNodeWithText("vibration")
		row.performTouchInput { down(center); cancel() }
		rule.runOnIdle { assertEquals(0, feedback) }
		row.performTouchInput { down(center); moveBy(Offset(0f, 150f)); up() }
		rule.runOnIdle { assertEquals(0, feedback) }
		row.performTouchInput { click() }
		rule.runOnIdle {
			assertEquals(1, feedback)
			assertEquals(AppRoute.SettingsVibration, navigator.current)
		}
	}
	@Test
	fun appNavigationDeliversFeedbackAcrossComposeLayers() {
		var feedback = 0
		val context = rule.activity
		val settings = context.getString(org.materialclass.R.string.nav_settings)
		val vibration = context.getString(org.materialclass.R.string.vibration)
		rule.setContent { MaterialClassApp(startAuthenticated = true, onClickFeedback = { feedback++ }) }
		rule.onNodeWithText(settings).performTouchInput { click() }
		rule.waitUntil(5_000) { rule.onAllNodesWithText(vibration).fetchSemanticsNodes().isNotEmpty() }
		rule.runOnIdle { assertEquals(1, feedback) }
		rule.onNodeWithText(vibration).performTouchInput { click() }
		rule.runOnIdle { assertEquals(2, feedback) }
	}

	@Test
	fun vibrationStrengthRemainsWithinAndroidAmplitudeLimits() {
		val preferences = AppPreferences(rule.activity)
		val original = preferences.vibrationStrength()
		try {
			preferences.setVibrationStrength(Int.MAX_VALUE)
			assertEquals(255, preferences.vibrationStrength())
			preferences.setVibrationStrength(Int.MIN_VALUE)
			assertEquals(1, preferences.vibrationStrength())
		} finally {
			preferences.setVibrationStrength(original)
		}
	}

	@Test
	fun emptyAreaAndDisabledControlDoNotProvideFeedback() {
		var feedback = 0
		var clicks = 0
		rule.setContent {
			CompositionLocalProvider(LocalClickFeedback provides { feedback++ }) {
				Box(Modifier.fillMaxSize().testTag("empty")) {
					Button(onClick = clickFeedback { clicks++ }, enabled = false) { Text("disabled") }
				}
			}
		}
		rule.onNodeWithTag("empty").performTouchInput { click(bottomRight - Offset(10f, 10f)) }
		rule.onNodeWithText("disabled").performTouchInput { click() }
		rule.runOnIdle {
			assertEquals(0, feedback)
			assertEquals(0, clicks)
		}
	}

}
