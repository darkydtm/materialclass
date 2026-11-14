package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.materialclass.api.GatewayLoginResult
import org.junit.Rule
import org.junit.Test

class MaterialClassNavigationTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun cancelledEdgeSwipeKeepsDetailAndShowsPreviousDuringDrag() {
		lateinit var layout: PredictiveBackLayout
		var closes = 0
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
						{ Box(Modifier.fillMaxSize().background(Color.Blue)) },
						{ Box(Modifier.fillMaxSize().background(Color.Yellow)) },
					) { closes++ }
				},
			)
		}
		composeRule.waitForIdle()
		val position = IntArray(2)
		composeRule.runOnUiThread { layout.getLocationOnScreen(position) }
		val startX = position[0] + 10f
		val y = position[1] + layout.height / 2f
		val downTime = SystemClock.uptimeMillis()
		fun send(action: Int, x: Float) {
			val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
			try {
				InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
			} finally {
				event.recycle()
			}
		}
		send(MotionEvent.ACTION_DOWN, startX)
		for (step in 1..10) {
			SystemClock.sleep(20)
			send(MotionEvent.ACTION_MOVE, startX + layout.width * 0.6f * step / 10)
		}
		composeRule.waitForIdle()
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		check(pixels[pixels.width / 4, pixels.height / 2] == Color.Blue) { "Swipe must reveal the previous screen" }
		check(pixels[pixels.width * 3 / 4, pixels.height / 2] == Color.Yellow) { "Swipe must retain the detail screen" }
		send(MotionEvent.ACTION_CANCEL, startX + layout.width * 0.6f)
		composeRule.waitUntil(5_000) { layout.getChildAt(3).left == 0 }
		composeRule.runOnIdle { check(closes == 0) { "Cancelled swipe closed the detail screen" } }
	}

	@Test
	fun predictiveProgressRevealsThePreviousScreenWithoutTintingIt() {
		lateinit var layout: PredictiveBackLayout
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						backgroundColor = android.graphics.Color.BLACK,
						gestureEnabled = true,
						previousRoute = AppRoute.Settings,
						currentRoute = AppRoute.SettingsTheme,
						previousContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
						currentContent = { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
						onClose = {},
					)
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread { layout.setPredictiveProgress(0.5f) }
		composeRule.waitForIdle()
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		check(pixels[pixels.width / 4, pixels.height / 2] == Color.Blue)
		check(pixels[pixels.width * 3 / 4, pixels.height / 2] == Color.Yellow)
	}

	@Test
	fun openingSecondaryScreenStartsAtRightEdge() {
		lateinit var layout: PredictiveBackLayout
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						backgroundColor = android.graphics.Color.BLACK,
						gestureEnabled = false,
						previousRoute = null,
						currentRoute = AppRoute.Settings,
						previousContent = null,
						currentContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
						onClose = {},
					)
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread {
			layout.updateLayers(
				backgroundColor = android.graphics.Color.BLACK,
				gestureEnabled = true,
				previousRoute = AppRoute.Settings,
				currentRoute = AppRoute.SettingsTheme,
				previousContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
				currentContent = { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
				onClose = {},
			)
			check(layout.width > 0)
			check(layout.getChildAt(3).left == layout.width) { "Opening screen must start at the right edge" }
		}
	}

	@Test
	fun previousScreenEffectDoesNotRestartWhenOpeningDetail() {
		lateinit var layout: PredictiveBackLayout
		var loads = 0
		val previous: @Composable () -> Unit = {
			LaunchedEffect(Unit) { loads++ }
			Box(Modifier.fillMaxSize().background(Color.Blue))
		}
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
				},
			)
		}
		composeRule.waitForIdle()
		check(loads == 1)
		composeRule.runOnUiThread {
			layout.updateLayers(
				android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
				previous, { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
			) { }
		}
		composeRule.waitForIdle()
		check(loads == 1) { "Opening detail restarted the previous screen effect: $loads" }
	}

	@Test
	fun returningToPreviousScreenDoesNotRestartItsEffect() {
		lateinit var layout: PredictiveBackLayout
		var loads = 0
		val previous: @Composable () -> Unit = {
			LaunchedEffect(Unit) { loads++ }
			Box(Modifier.fillMaxSize().background(Color.Blue))
		}
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread {
			layout.updateLayers(
				android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
				previous, { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
			) { }
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread {
			layout.commitPredictiveBack()
			layout.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
		}
		composeRule.waitForIdle()
		check(loads == 1) { "Returning to previous screen restarted its effect: $loads" }
	}

	@Test
	fun loginFailureShowsLocalizedValidation() {
		composeRule.activity.setContent {
			MaterialClassApp(loginHandler = { GatewayLoginResult.Failure(org.materialclass.api.AppError.InvalidCredentials) })
		}
		composeRule.onNodeWithTag("login-button").performClick()
		composeRule.onNodeWithText("Проверьте логин и пароль").assertIsDisplayed()
	}

	@Test
	fun routeNavigatorBackReturnsToParent() {
		val navigator = AppNavigator(AppRoute.Home)
		navigator.go(AppRoute.Schedule)
		navigator.go(AppRoute.LessonDetail)
		check(navigator.back())
		check(navigator.current == AppRoute.Schedule)
	}

	@Test
	fun dayProgressCardFillsThePaddedHomeWidth() {
		composeRule.activity.setContent {
			org.materialclass.ui.theme.MaterialClassTheme(ThemeMode.BLACK) {
				HomeScreen(PaddingValues(0.dp), { it }, SchoolViewModel(), AppNavigator(AppRoute.Home)) { }
			}
		}
		val home = composeRule.onNodeWithTag("home-screen").getUnclippedBoundsInRoot()
		val card = composeRule.onNodeWithTag("day-progress-card").getUnclippedBoundsInRoot()
		check(card.left == home.left + 20.dp)
		check(card.right == home.right - 20.dp)
	}
}