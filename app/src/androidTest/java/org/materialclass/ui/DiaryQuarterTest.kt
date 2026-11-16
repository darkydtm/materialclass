package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.materialclass.api.GatewayReadResult
import org.materialclass.data.*

class DiaryQuarterTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun initialCurrentQuarterLoadsAndFutureQuarterShowsMessageWithoutGradesRequest() {
		val repository = QuarterRepository()
		val selected = CopyOnWriteArrayList<String>()
		val viewModel = SchoolViewModel(repository, initialGradesPeriod = "1", onGradesPeriod = { selected.add(it) })
		composeRule.setContent {
			DiaryScreen(PaddingValues(0.dp), { it }, viewModel, AppNavigator(AppRoute.Diary)) {}
		}
		composeRule.waitUntil(5_000) { repository.requested.contains("2") }
		composeRule.runOnIdle {
			assertEquals("2", viewModel.gradesPeriod)
			assertEquals(listOf("2"), selected.toList())
			viewModel.selectGradesPeriod("3")
		}
		composeRule.waitUntil(5_000) { viewModel.quarterNotStarted }
		composeRule.onNodeWithText("quarter_not_started").assertIsDisplayed()
		composeRule.runOnIdle { assertEquals(listOf("2"), repository.requested.toList()) }
	}

	private class QuarterRepository : SchoolRepository {
		val requested = CopyOnWriteArrayList<String>()
		private val formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
		override fun diaryPeriods(): GatewayReadResult<PeriodsModel> {
			val today = LocalDate.now()
			fun period(number: Int, start: Long, end: Long) = DiaryPeriod(number, today.plusDays(start).format(formatter), today.plusDays(end).format(formatter))
			return GatewayReadResult.Success(PeriodsModel(4, listOf(period(1, -100, -50), period(2, -30, 30), period(3, 40, 80), period(4, 90, 120))))
		}
		override fun observeHome() = RepositoryObservation<HomeModel>(DataState.Loading)
		override fun observeDiary(range: DateRange) = RepositoryObservation<DiaryModel>(DataState.Loading)
		override fun observeSchedule(range: DateRange) = RepositoryObservation<ScheduleModel>(DataState.Loading)
		override fun observeGrades(period: String) = RepositoryObservation<GradesModel>(DataState.Loading)
		override fun observeHomeworks() = RepositoryObservation<HomeworksModel>(DataState.Loading)
		override fun observeJournal(range: DateRange) = RepositoryObservation<JournalGradesModel>(DataState.Loading)
		override fun observeProfile() = RepositoryObservation<ProfileModel>(DataState.Loading)
		override fun refreshGrades(period: String) { requested.add(period) }
		override fun refreshHome() = Unit
		override fun refreshDiary(range: DateRange) = Unit
		override fun refreshSchedule(range: DateRange) = Unit
		override fun refreshHomeworks() = Unit
		override fun refreshJournal(range: DateRange) = Unit
		override fun refreshProfile() = Unit
		override fun logout() = Unit
	}
}
