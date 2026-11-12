package org.materialclass.grades

enum class GradeCategory {
	FO,
	SOR,
	SOCH,
}

data class GradeComponent(
	val category: GradeCategory,
	val score: Double?,
) {
	init {
		if (score != null) {
			require(score.isFinite() && score in 0.0..100.0) {
				"Grade score must be a finite percentage from 0 to 100"
			}
		}
	}
}

data class GradeEstimate(
	val percentage: Double?,
	val grade: Int?,
	val availableCategories: Set<GradeCategory>,
	val missingCategories: Set<GradeCategory>,
)

object GradeCalculator {
	private val categoryWeights = mapOf(
		GradeCategory.FO to 0.50,
		GradeCategory.SOR to 0.25,
		GradeCategory.SOCH to 0.25,
	)

	fun calculate(components: List<GradeComponent>): GradeEstimate {
		val averages = components
			.asSequence()
			.filter { it.score != null }
			.groupingBy { it.category }
			.fold(0.0 to 0) { (sum, count), component ->
				(sum + component.score!! to count + 1)
			}
			.mapValues { (_, total) -> total.first / total.second }

		val availableCategories = averages.keys
		val missingCategories = GradeCategory.entries.toSet() - availableCategories
		if (availableCategories.isEmpty()) {
			return GradeEstimate(
				percentage = null,
				grade = null,
				availableCategories = emptySet(),
				missingCategories = missingCategories,
			)
		}

		val availableWeight = availableCategories.sumOf { categoryWeights.getValue(it) }
		val percentage = availableCategories.sumOf { category ->
			averages.getValue(category) * categoryWeights.getValue(category)
		} / availableWeight

		return GradeEstimate(
			percentage = percentage,
			grade = percentage.toGrade(),
			availableCategories = availableCategories,
			missingCategories = missingCategories,
		)
	}

	private fun Double.toGrade(): Int = when {
		this >= 85.0 -> 5
		this >= 65.0 -> 4
		this >= 35.0 -> 3
		this >= 20.0 -> 2
		else -> 1
	}
}
