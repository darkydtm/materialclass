package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.materialclass.data.ClassmateMark
import org.materialclass.data.ComparisonMark
import org.materialclass.data.GradeComparison
import org.materialclass.ui.theme.MaterialClassTheme

class GradeComparisonThemeTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun comparisonCardsUseThemeColorsInAmoled() {
		var primaryContainer = Color.Unspecified
		var lowSurface = Color.Unspecified
		var darkLowSurface = Color.Unspecified
		composeRule.setContent {
			MaterialClassTheme(ThemeMode.DARK) {
				darkLowSurface = MaterialTheme.colorScheme.surfaceContainerLow
			}
			MaterialClassTheme(ThemeMode.BLACK) {
				primaryContainer = MaterialTheme.colorScheme.primaryContainer
				lowSurface = MaterialTheme.colorScheme.surfaceContainerLow
				Column {
					ComparisonContent(GradeComparison(10, ComparisonMark(8, null), emptyList<ClassmateMark>())) { it }
				}
			}
		}
		composeRule.waitForIdle()
		assertNotEquals(darkLowSurface, lowSurface)
		val summary = composeRule.onNodeWithTag("comparison-summary").captureToImage().toPixelMap()
		val chart = composeRule.onNodeWithTag("comparison-chart").captureToImage().toPixelMap()
		assertEquals(primaryContainer, summary[summary.width / 2, 2])
		assertEquals(lowSurface, chart[chart.width / 2, 2])
	}
}
