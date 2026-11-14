package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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