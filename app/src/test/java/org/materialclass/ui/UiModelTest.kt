package org.materialclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
	@Test
	fun subjectFinalGradeUsesAvailableMarksWhenServerGradeIsMissing() {
		val subject = org.materialclass.data.GradeSubject(
			subjectName = "Math",
			finalScore = null,
			schedules = listOf(org.materialclass.data.GradeSchedule("lesson", null, null, 10, null, score = "8")),
		)
		val helper = Class.forName("org.materialclass.ui.AppModelsKt").declaredMethods.firstOrNull { it.name == "effectiveFinalScore" }
		assertTrue("A shared final grade calculation must exist", helper != null)
		helper!!.isAccessible = true
		assertEquals("4", helper.invoke(null, subject))
	}

	@Test
	fun finalGradePreservesServerValueAndHandlesMissingCategories() {
		val schedule = org.materialclass.data.GradeSchedule("lesson", null, null, 10, null, score = "8", sor = "6", sorMax = "10")
		val subject = org.materialclass.data.GradeSubject("Math", null, listOf(schedule))
		assertEquals("4", effectiveFinalScore(subject))
		assertEquals("5", effectiveFinalScore(subject.copy(finalScore = "5")))
		assertEquals(null, effectiveFinalScore(subject.copy(scoreType = "credit")))
		assertEquals(null, effectiveFinalScore(subject.copy(schedules = emptyList())))
	}

	@Test
	fun numericGradeColorIsConsistentWithinEachGradeBand() {
		val helper = Class.forName("org.materialclass.ui.AppModelsKt").declaredMethods.firstOrNull { it.name == "gradeHue" }
		assertTrue("Grade hue must be calculated independently of theme accents", helper != null)
		helper!!.isAccessible = true
		listOf(0.0 to 0f, 25.0 to 30f, 50.0 to 60f, 75.0 to 90f, 100.0 to 120f).forEach { (percent, expectedHue) ->
			assertEquals(expectedHue, helper.invoke(null, percent) as Float, 0.001f)
		}
		assertEquals(0f, helper.invoke(null, -10.0) as Float, 0.001f)
		assertEquals(120f, helper.invoke(null, 150.0) as Float, 0.001f)
		assertEquals(0f, helper.invoke(null, Double.NaN) as Float, 0.001f)
	}

	@Test
	fun displayedMarksUseTheirActualScaleForGradeColor() {
		assertEquals(48f, gradeColorHue("4", "numeric", 10.0)!!, 0.001f)
		assertEquals(96f, gradeColorHue("4", "numeric", 5.0)!!, 0.001f)
		assertEquals(0f, gradeColorHue("0", "numeric", 10.0)!!, 0.001f)
		assertEquals(120f, gradeColorHue("10", "numeric", 10.0)!!, 0.001f)
		assertEquals(null, gradeColorHue("Зачет", "credit", 10.0))
		assertEquals(null, gradeColorHue("", "numeric", 10.0))
		assertEquals(null, gradeColorHue("4", "numeric", 0.0))
	}

	@Test
	fun finalGradesUseGradeBandsInsteadOfFivePointPercentages() {
		assertEquals(0f, finalGradeColorHue("1", "numeric")!!, 0.001f)
		assertEquals(30f, finalGradeColorHue("2", "numeric")!!, 0.001f)
		assertEquals(gradeColorHue("2", "numeric", 5.0), finalGradeColorHue("2", "numeric"))
		assertEquals(48f, gradeColorHue("2", "numeric", 5.0)!!, 0.001f)
		assertEquals(60f, finalGradeColorHue("3", "numeric")!!, 0.001f)
		assertEquals(90f, finalGradeColorHue("4", "numeric")!!, 0.001f)
		assertEquals(120f, finalGradeColorHue("5", "numeric")!!, 0.001f)
		assertEquals(null, finalGradeColorHue("Зачет", "credit"))
		assertEquals(null, finalGradeColorHue("", "numeric"))
	}

	@Test
	fun profileNameNormalizesEveryPart() {
		assertEquals("Иванов Иван Иванович", normalizedFullName("  ИВАНОВ   иВАН ИВАНОВИЧ "))
		assertEquals(null, normalizedFullName("  "))
	}

	@Test
	fun selectingQuarterUpdatesAuthoritativePeriod() {
		val viewModel = SchoolViewModel()
		val method = viewModel.javaClass.methods.firstOrNull { it.name == "selectGradesPeriod" }
		assertTrue("Quarter selection must update the shared period", method != null)
		listOf("2", "3", "4", "1").forEach { period ->
			method!!.invoke(viewModel, period)
			assertEquals(period, viewModel.gradesPeriod)
		}
	}

	@Test
	fun staleScreenCannotOpenAnotherDestination() {
		val navigator = AppNavigator(AppRoute.Settings)
		var selections = 0
		val method = navigator.javaClass.methods.firstOrNull { it.name == "goFrom" }
		assertTrue("Navigation must reject stale screen actions", method != null)
		val select: () -> Unit = { selections++ }
		method!!.invoke(navigator, AppRoute.Settings, AppRoute.SettingsTheme, select)
		method.invoke(navigator, AppRoute.Settings, AppRoute.SettingsNotifications, select)
		assertEquals(AppRoute.SettingsTheme, navigator.current)
		assertEquals(1, selections)
		assertTrue(navigator.back())
		assertEquals(AppRoute.Settings, navigator.current)
	}

	@Test
	fun primaryBackReturnsHomeWithoutRestoringSecondaryHistory() {
		val navigator = AppNavigator(AppRoute.Home)
		navigator.go(AppRoute.Settings)
		navigator.go(AppRoute.SettingsTheme)
		navigator.go(AppRoute.Schedule)
		assertTrue(navigator.handlePrimaryBack())
		assertEquals(AppRoute.Home, navigator.current)
		assertEquals(false, navigator.handlePrimaryBack())
	}

	@Test
	fun completedLessonsUseEndTimesRatherThanThemes() {
		val lessons = listOf(
			org.materialclass.data.ScheduleLesson("a", "Math", "08:00 - 08:45", null, null, null, null, null),
			org.materialclass.data.ScheduleLesson("b", "Art", "09:00 - 09:45", null, null, null, "Theme", null),
		)
		assertEquals(1, completedLessonCount(lessons, java.time.LocalTime.of(9, 0)))
	}

	@Test
	fun weekendDetectionSeparatesSaturdayAndSundayFromWeekdays() {
		assertTrue(isWeekend(java.time.LocalDate.of(2026, 11, 14)))
		assertTrue(isWeekend(java.time.LocalDate.of(2026, 11, 15)))
		assertEquals(false, isWeekend(java.time.LocalDate.of(2026, 11, 13)))
	}

	@Test
	fun interactiveScreensUse120HzFrameBudget() {
		assertEquals(8.3f, INTERACTIVE_FRAME_BUDGET_MS)
		assertEquals(AppRoute.entries.toSet(), interactiveScreenFrameBudgets.keys)
		assertTrue(interactiveScreenFrameBudgets.values.all { it == 8.3f })
	}

	@Test
	fun routeStackStartsAtLoginAndBackDoesNotLeaveRoot() {
		val navigator = AppNavigator(AppRoute.Login)
		navigator.go(AppRoute.Schedule)
		navigator.go(AppRoute.LessonDetail)

		assertEquals(AppRoute.LessonDetail, navigator.current)
		assertEquals(AppRoute.Schedule, navigator.previousOrNull())
		assertTrue(navigator.back())
		assertEquals(AppRoute.Schedule, navigator.current)
		assertTrue(navigator.back())
		assertEquals(AppRoute.Login, navigator.current)
		assertEquals(false, navigator.back())
	}

	@Test
	fun selectingPrimaryRouteClearsSecondaryHistory() {
		val navigator = AppNavigator(AppRoute.Home)
		navigator.go(AppRoute.Settings)
		navigator.go(AppRoute.SettingsTheme)
		navigator.go(AppRoute.Diary)

		assertEquals(AppRoute.Diary, navigator.current)
		assertEquals(false, navigator.back())
	}

	@Test
	fun unsupportedContractIsRenderedAsUnavailableState() {
		assertEquals(UiContent.Unsupported, UiContent.from(DataStateKind.Unsupported))
		assertEquals(UiContent.Empty, UiContent.from(DataStateKind.Empty))
		assertEquals(UiContent.Error, UiContent.from(DataStateKind.Error))
	}

	@Test
	fun routeTitleUsesTheSelectedScreen() {
		assertEquals("nav_home", AppRoute.Home.titleKey())
		assertEquals("theme", AppRoute.SettingsTheme.titleKey())
		assertEquals("nav_settings", AppRoute.Settings.titleKey())
		assertEquals("lesson_title", AppRoute.LessonDetail.titleKey())
	}

	@Test
	fun stableItemKeysRemainUniqueWithoutServerIds() {
		val lessons = listOf(
			org.materialclass.data.ScheduleLesson(null, "Math", "08:00", null, null, null, null, null),
			org.materialclass.data.ScheduleLesson(null, "Math", "08:00", null, null, null, null, null),
		)
		assertEquals(2, scheduleLessonStableKeys(lessons).toSet().size)
	}

	@Test
	fun gradeScheduleKeysUseServerIdWhenAvailable() {
		val schedules = listOf(
			org.materialclass.data.GradeSchedule("schedule-42", null, null, 10, "SOR"),
		)
		assertEquals(listOf("grade:11:schedule-42:0"), gradeScheduleStableKeys(schedules))
	}

	@Test
	fun duplicateSuffixCannotCollideWithAnotherServerId() {
		val schedules = listOf(
			org.materialclass.data.GradeSchedule("a", null, null, 10, "SOR"),
			org.materialclass.data.GradeSchedule("a", null, null, 10, "SOR"),
			org.materialclass.data.GradeSchedule("a:1", null, null, 10, "SOR"),
		)
		assertEquals(schedules.size, gradeScheduleStableKeys(schedules).toSet().size)
	}

	@Test
	fun preparedRowsMoveFormattingOutOfLazyItems() {
		val lesson = org.materialclass.data.ScheduleLesson(null, "Math", "08:00", "regular", null, "lesson", "", "homework")
		val row = scheduleLessonRows(listOf(lesson), "2026-11-14").single()
		assertEquals(null, row.cabinet)
		assertEquals("lesson", row.type)
		assertEquals("homework", row.homework)
		assertEquals(lesson, findScheduleLesson(listOf(org.materialclass.data.ScheduleDay("2026-11-14", false, false, listOf(lesson))), row.stableKey))
	}

	@Test
	fun scheduleDayScopeDoesNotUsePositionAsIdentity() {
		val day = org.materialclass.data.ScheduleDay(null, false, false, emptyList())
		assertEquals("empty-day", scheduleDayScope(day))
	}

	@Test
	fun subjectRowsPrepareDisplayValuesBeforeLazyComposition() {
		val rows = gradeSubjectRows(
			listOf(org.materialclass.data.GradeSubject("Math", null, emptyList(), "5", null, null, null)),
			"unknown",
			"no data",
		)
		assertEquals("Math", rows.single().name)
		assertEquals("no data", rows.single().finalScore)
		assertEquals("no data", rows.single().quarter2)
	}

	@Test
	fun gradeSubjectSelectionUsesPreparedStableKey() {
		val subjects = listOf(
			org.materialclass.data.GradeSubject("Math", "5", emptyList()),
			org.materialclass.data.GradeSubject("Math", "4", emptyList()),
		)
		val keys = gradeSubjectStableKeys(subjects)
		assertEquals("5", findGradeSubject(subjects, keys[0])?.finalScore)
		assertEquals("4", findGradeSubject(subjects, keys[1])?.finalScore)
	}

	@Test
	fun gradeSubjectKeyDoesNotChangeWhenScoresUpdate() {
		val before = listOf(org.materialclass.data.GradeSubject("Math", "5", emptyList()))
		val after = listOf(org.materialclass.data.GradeSubject("Math", "4", emptyList()))
		assertEquals(gradeSubjectStableKeys(before), gradeSubjectStableKeys(after))
	}

	@Test
	fun assessmentTypeUsesCreditLabelsAndKeepsNumbersNumeric() {
		assertEquals(AssessmentKind.Credit, assessmentKind("Зачет"))
		assertEquals(AssessmentKind.Fail, assessmentKind("Незачет"))
		assertEquals(AssessmentKind.Numeric, assessmentKind("8"))
		assertEquals(AssessmentKind.Credit, assessmentKind("ok", "credit"))
		assertEquals("Зачет", assessmentText("pass", null, "Зачет", "Незачет", "Нет данных"))
		assertEquals("8", assessmentText("8", null, "Зачет", "Незачет", "Нет данных"))
	}

	@Test
	fun assessmentMaximumIsShownOnlyForNumericValuesWithKnownMaximum() {
		fun display(value: String?, maximum: String?) = assessmentWithMaximum(value, null, maximum, "pass", "fail", "no data")
		assertEquals("12/15", display("12", "15"))
		assertEquals("0/30", display("0", "30"))
		assertEquals("12", display("12", null))
		assertEquals("12/15", display("12/15", "15"))
		assertEquals("no data", display(null, "15"))
		assertEquals("pass", display("pass", "15"))
	}

	@Test
	fun gradeComponentsCollapseRepeatedLessonCategoryValuesButKeepDistinctScoresAndLessons() {
		val lesson1 = org.materialclass.data.GradeSchedule("lesson-1", null, null, 10, null, score = "8", sor = "6", sorMax = "10", soch = "12", sochMax = "15")
		val mixedDuplicate = lesson1.copy(score = "9", soch = null)
		val differentAssessments = lesson1.copy(sor = "8")
		val lesson2 = lesson1.copy(uuid = "lesson-2")

		val components = gradeComponents(listOf(lesson1, mixedDuplicate, differentAssessments, lesson2))

		assertEquals(3, components.count { it.category == org.materialclass.grades.GradeCategory.FO })
		assertEquals(listOf(60.0, 60.0, 80.0), components.filter { it.category == org.materialclass.grades.GradeCategory.SOR }.mapNotNull { it.score }.sorted())
		assertEquals(2, components.count { it.category == org.materialclass.grades.GradeCategory.SOCH })
	}

	@Test
	fun formativeComponentsUseFullPercentageScaleForFinalGrade() {
		val schedule = org.materialclass.data.GradeSchedule("lesson", null, null, 10, null, score = "10", sor = "8", sorMax = "10", soch = "6", sochMax = "10")
		val subject = org.materialclass.data.GradeSubject("Math", null, listOf(schedule))
		val components = gradeComponents(subject.schedules)

		assertEquals(100.0, org.materialclass.grades.GradeCalculator.categoryPercentage(components, org.materialclass.grades.GradeCategory.FO)!!, 0.000001)
		assertEquals(85.0, org.materialclass.grades.GradeCalculator.calculate(components).percentage!!, 0.000001)
		assertEquals("5", effectiveFinalScore(subject))
	}

	@Test
	fun journalGradesMatchLessonUuidAndPreserveAllAssessments() {
		val grade = org.materialclass.data.JournalGrade("lesson-1", null, null, "12", "24", null, "8", "10", null, "15", "30")
		val journal = org.materialclass.data.JournalGradesModel(listOf(grade), 1, 0)
		val lesson = org.materialclass.data.ScheduleLesson("lesson-1", null, null, null, null, null, null, null)
		val result = journalGradeForLesson(journal, lesson)!!
		assertEquals("8", result.score)
		assertEquals(10, result.markMax)
		assertEquals("12", result.sor)
		assertEquals("15", result.sorMax)
		assertEquals("24", result.soch)
		assertEquals("30", result.sochMax)
		assertEquals(null, journalGradeForLesson(journal, lesson.copy(uuid = "another")))
	}

	@Test
	fun creditSubjectIsDetectedFromFinalOrLessonAssessment() {
		assertTrue(isCreditSubject(org.materialclass.data.GradeSubject("Safety", "Зачет", emptyList())))
		assertTrue(isCreditSubject(org.materialclass.data.GradeSubject("Safety", null, listOf(org.materialclass.data.GradeSchedule("lesson", null, null, null, null, "Незачет")))))
	}
}
