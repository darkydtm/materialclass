package org.materialclass.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.api.AppError

class ModelsTest {
	private val accountId = AccountId("synthetic-account")
	private val generation = Generation(7)
	private val capturedAt = Instant.parse("2026-01-02T03:04:05Z")

	@Test
	fun activeSessionContainsOnlyAccountAndGeneration() {
		val session = ActiveSession(accountId, generation)

		assertEquals(accountId, session.accountId)
		assertEquals(generation, session.generation)
		assertFalse(session.toString().contains("token", ignoreCase = true))
	}

	@Test
	fun dateRangeRequiresAnOrderedInclusiveRange() {
		val range = DateRange(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 8))

		assertEquals(LocalDate.of(2026, 1, 2), range.start)
		assertEquals(LocalDate.of(2026, 1, 8), range.endInclusive)
	}

	@Test(expected = IllegalArgumentException::class)
	fun dateRangeRejectsReversedBounds() {
		DateRange(LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 2))
	}

	@Test
	fun snapshotCarriesOnlyDataProvenance() {
		val snapshot = Snapshot(accountId, generation, "synthetic", capturedAt)

		assertEquals("synthetic", snapshot.value)
		assertEquals(capturedAt, snapshot.capturedAt)
		assertEquals(accountId, snapshot.accountId)
		assertEquals(generation, snapshot.generation)
	}

	@Test
	fun outcomeDistinguishesEmptySuccessFromFailure() {
		val empty = Outcome.Empty<String>(capturedAt)
		val failure = Outcome.Failure<String>(AppError.Offline)

		assertEquals(DataState.Empty, empty.toState())
		assertEquals(DataState.Offline<String>(), failure.toState())
	}

	@Test
	fun outcomeMapsSuccessToReadyAndOtherErrorsToError() {
		val snapshot = Snapshot(accountId, generation, "synthetic", capturedAt)
		val success = Outcome.Success(snapshot)
		val failure = Outcome.Failure<String>(AppError.Forbidden)

		assertEquals(DataState.Ready(snapshot), success.toState())
		assertEquals(DataState.Error<String>(AppError.Forbidden), failure.toState())
	}

	@Test
	fun loadingIsNotRepresentedByAnEmptyState() {
		assertFalse(DataState.Loading == DataState.Empty)
	}

	@Test
	fun offlineCanRetainAStaleSnapshotWithoutBecomingAnError() {
		val snapshot = Snapshot(accountId, generation, "cached", capturedAt)
		val state = DataState.Stale(snapshot)

		assertEquals(snapshot, state.snapshot)
		assertNotNull(state.snapshot)
		assertFalse(state == DataState.Error<String>(AppError.Offline))
	}

	@Test
	fun confirmedDomainModelsStayNeutralAndDoNotCarrySecrets() {
		val home = HomeModel
		val diary = DiaryModel(dayCount = 7)
		val schedule = ScheduleModel(dayCount = 7)
		val grades = GradesModel(subjectCount = 3)
		val profile = ProfileModel(accountId = accountId, schoolLabel = "Synthetic school", groupLabel = "Synthetic group")
		val notifications = NotificationsModel

		assertNotNull(home)
		assertEquals(7, diary.dayCount)
		assertEquals(7, schedule.dayCount)
		assertEquals(3, grades.subjectCount)
		assertEquals(accountId, profile.accountId)
		assertEquals("Synthetic school", profile.schoolLabel)
		assertEquals("Synthetic group", profile.groupLabel)
		assertNotNull(notifications)
	}
}
