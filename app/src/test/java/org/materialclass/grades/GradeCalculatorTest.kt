package org.materialclass.grades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeCalculatorTest {
	@Test
	fun noComponentsReturnNoEstimateAndMarkEveryCategoryMissing() {
		val estimate = GradeCalculator.calculate(emptyList())

		assertEquals(null, estimate.percentage)
		assertEquals(null, estimate.grade)
		assertTrue(estimate.availableCategories.isEmpty())
		assertEquals(GradeCategory.values().toSet(), estimate.missingCategories)
	}

	@Test
	fun nullScoresDoNotCreateAnAvailableCategory() {
		val estimate = GradeCalculator.calculate(
			listOf(
				GradeComponent(GradeCategory.FO, null),
				GradeComponent(GradeCategory.SOR, null),
			),
		)

		assertEquals(null, estimate.percentage)
		assertEquals(null, estimate.grade)
		assertTrue(estimate.availableCategories.isEmpty())
		assertEquals(GradeCategory.values().toSet(), estimate.missingCategories)
	}

	@Test
	fun formativeAndSummativeScoresAreAveragedBeforeApplyingWeights() {
		val estimate = GradeCalculator.calculate(
			listOf(
				GradeComponent(GradeCategory.FO, 80.0),
				GradeComponent(GradeCategory.FO, 90.0),
				GradeComponent(GradeCategory.SOR, 60.0),
				GradeComponent(GradeCategory.SOR, 80.0),
				GradeComponent(GradeCategory.SOCH, 100.0),
			),
		)

		assertEquals(85.0, estimate.percentage!!, 0.000001)
		assertEquals(5, estimate.grade)
		assertEquals(GradeCategory.values().toSet(), estimate.availableCategories)
		assertTrue(estimate.missingCategories.isEmpty())
	}

	@Test
	fun missingCategoriesAreReportedAndRemainingWeightsAreNormalized() {
		val onlyFormative = GradeCalculator.calculate(listOf(GradeComponent(GradeCategory.FO, 80.0)))
		val onlySummative = GradeCalculator.calculate(listOf(GradeComponent(GradeCategory.SOR, 65.0)))
		val onlyFinal = GradeCalculator.calculate(listOf(GradeComponent(GradeCategory.SOCH, 35.0)))
		val formativeAndSummative = GradeCalculator.calculate(
			listOf(
				GradeComponent(GradeCategory.FO, 80.0),
				GradeComponent(GradeCategory.SOR, 60.0),
			),
		)

		assertEquals(80.0, onlyFormative.percentage!!, 0.000001)
		assertEquals(setOf(GradeCategory.SOR, GradeCategory.SOCH), onlyFormative.missingCategories)
		assertEquals(65.0, onlySummative.percentage!!, 0.000001)
		assertEquals(setOf(GradeCategory.FO, GradeCategory.SOCH), onlySummative.missingCategories)
		assertEquals(35.0, onlyFinal.percentage!!, 0.000001)
		assertEquals(setOf(GradeCategory.FO, GradeCategory.SOR), onlyFinal.missingCategories)
		assertEquals(73.33333333333333, formativeAndSummative.percentage!!, 0.000001)
		assertEquals(setOf(GradeCategory.SOCH), formativeAndSummative.missingCategories)
	}

	@Test
	fun exactThresholdsMapToExpectedGrades() {
		assertGrade(85.0, 5)
		assertGrade(65.0, 4)
		assertGrade(35.0, 3)
		assertGrade(20.0, 2)
		assertGrade(19.999999, 1)
	}

	@Test
	fun valuesJustBelowEachThresholdMapToTheLowerGrade() {
		assertGrade(84.999999, 4)
		assertGrade(64.999999, 3)
		assertGrade(34.999999, 2)
		assertGrade(19.999999, 1)
	}

	@Test
	fun valuesJustAboveEachThresholdMapToTheThresholdGrade() {
		assertGrade(85.000001, 5)
		assertGrade(65.000001, 4)
		assertGrade(35.000001, 3)
		assertGrade(20.000001, 2)
	}

	private fun assertGrade(score: Double, expectedGrade: Int) {
		val estimate = GradeCalculator.calculate(listOf(GradeComponent(GradeCategory.FO, score)))

		assertEquals(score, estimate.percentage!!, 0.000001)
		assertEquals(expectedGrade, estimate.grade)
	}
}
