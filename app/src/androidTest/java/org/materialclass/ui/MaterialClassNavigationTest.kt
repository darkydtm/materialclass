package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
}