package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class DayProgressIndicatorTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun exposesCompletedLessonsAsAccessibleProgress() {
		composeRule.setContent {
			DayProgressIndicator(completedLessons = 2, totalLessons = 4, label = "Today's progress")
		}

		composeRule.onNode(hasContentDescription("Today's progress"))
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)))
	}

	@Test
	fun emptyDayExposesZeroProgress() {
		composeRule.setContent {
			DayProgressIndicator(completedLessons = 0, totalLessons = 0, label = "Today's progress")
		}

		composeRule.onNode(hasContentDescription("Today's progress"))
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..1f)))
	}

	@Test
	fun completedDayExposesFullProgress() {
		composeRule.setContent {
			DayProgressIndicator(completedLessons = 4, totalLessons = 4, label = "Today's progress")
		}

		composeRule.onNode(hasContentDescription("Today's progress"))
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
	}
}
