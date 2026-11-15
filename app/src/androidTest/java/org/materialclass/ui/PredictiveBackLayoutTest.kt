package org.materialclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.drawToBitmap
import androidx.activity.ComponentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackLayoutTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun immediateNextBackAfterCompletedCloseWorksAndCancelledBackKeepsRoute() {
		lateinit var layout: PredictiveBackLayout
		val navigator = AppNavigator(AppRoute.Settings)
		navigator.go(AppRoute.SettingsTheme)
		navigator.go(AppRoute.BackgroundWork)
		var route by mutableStateOf(navigator.current)
		var closes = 0
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						android.graphics.Color.BLACK,
						gestureEnabled = navigator.canGoBack(),
						previousRoute = navigator.previousOrNull(),
						currentRoute = route,
						previousContent = { Box(Modifier.fillMaxSize().background(routeColor(navigator.previousOrNull()))) },
						currentContent = { Box(Modifier.fillMaxSize().background(routeColor(route))) },
					) {
						closes++
						navigator.back()
						route = navigator.current
					}
				},
			)
		}
		composeRule.waitForIdle()

		composeRule.runOnUiThread {
			layout.setPredictiveProgress(0.5f)
			layout.cancelPredictiveBack()
		}
		composeRule.runOnIdle {
			check(closes == 0) { "Cancelled back navigated away" }
			check(navigator.current == AppRoute.BackgroundWork) { "Cancelled back changed the route" }
			layout.commitPredictiveBack()
		}
		composeRule.waitUntil(5_000) { closes == 1 && navigator.current == AppRoute.SettingsTheme }
		composeRule.waitForIdle()
		check(captureCenterColor(layout) == Color.Green) { "The intermediate screen was not shown after the first back" }
		composeRule.runOnUiThread {
			layout.setPredictiveProgress(1f)
			layout.commitPredictiveBack()
			check(closes == 2) { "The immediate next back waited for a second closing animation" }
		}
		composeRule.waitUntil(5_000) {
			navigator.current == AppRoute.Settings && layout.getChildAt(3).left == 0
		}
		composeRule.waitForIdle()
		check(navigator.current == AppRoute.Settings) { "Two completed backs did not return to the root route" }
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		val pixel = pixels[pixels.width / 2, pixels.height / 2]
		check(pixel == Color.Blue) {
			"Root screen was not revealed: pixel=$pixel, route=${navigator.current}, previous=${navigator.previousOrNull()}, childLeft=${layout.getChildAt(3).left}, childWidth=${layout.getChildAt(3).width}"
		}
		check(layout.getChildAt(3).left == 0) { "The second completed back left the outgoing screen displaced" }
	}
	private fun captureCenterColor(view: PredictiveBackLayout): Color {
		val bitmap = view.drawToBitmap()
		return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).let(Color::valueOf)
	}

	private fun routeColor(route: AppRoute?): Color = when (route) {
		AppRoute.Settings -> Color.Blue
		AppRoute.SettingsTheme -> Color.Green
		AppRoute.BackgroundWork -> Color.Yellow
		else -> Color.Red
	}
}
