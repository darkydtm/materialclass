package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GradeComparisonHitboxTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun assessmentTypeAndDateOpenComparison() {
		val viewModel = SchoolViewModel()
		val navigator = AppNavigator(AppRoute.Diary)
		composeRule.setContent {
			GradeTile(
				value = "8",
				scoreType = null,
				date = "2026-11-16T09:00:00",
				type = "SOR",
				text = { it },
				uuid = "lesson-1",
				comparisonType = "sor",
				viewModel = viewModel,
				navigator = navigator,
			)
		}

		composeRule.onNodeWithText("2026-11-16").performClick()
		composeRule.runOnIdle {
			assertEquals(AppRoute.GradeComparison, navigator.current)
			assertEquals("lesson-1" to "sor", viewModel.comparisonSelection)
		}
	}

	@Test
	fun assessmentTypeAreaOpensComparison() {
		val viewModel = SchoolViewModel()
		val navigator = AppNavigator(AppRoute.Diary)
		composeRule.setContent {
			GradeTile(
				value = "8",
				scoreType = null,
				date = "2026-11-16",
				type = "SOR",
				text = { it },
				uuid = "lesson-2",
				comparisonType = "sor",
				viewModel = viewModel,
				navigator = navigator,
			)
		}

		composeRule.onNodeWithText("SOR").performClick()
		composeRule.runOnIdle {
			assertEquals(AppRoute.GradeComparison, navigator.current)
			assertEquals("lesson-2" to "sor", viewModel.comparisonSelection)
		}
	}
}