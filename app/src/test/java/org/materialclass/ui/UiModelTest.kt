package org.materialclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
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
	fun creditSubjectIsDetectedFromFinalOrLessonAssessment() {
		assertTrue(isCreditSubject(org.materialclass.data.GradeSubject("Safety", "Зачет", emptyList())))
		assertTrue(isCreditSubject(org.materialclass.data.GradeSubject("Safety", null, listOf(org.materialclass.data.GradeSchedule("lesson", null, null, null, null, "Незачет")))))
	}
}
