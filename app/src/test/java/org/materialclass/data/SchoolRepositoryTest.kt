package org.materialclass.data

import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.api.AppError
import org.materialclass.api.GatewayReadResult
import org.materialclass.api.ReadContext
import org.materialclass.diagnostics.DiaryDiagnostics

class SchoolRepositoryTest {
	private val account = AccountId("synthetic-account")
	private val range = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7))
	private val session = ActiveSession(account, Generation(1))
	private val capturedAt = Instant.parse("2026-01-02T03:04:05Z")

	@Test
	fun missingSessionFailureCannotOverwriteReplacementAccount() {
		val replacement = ActiveSession(AccountId("synthetic-replacement"), Generation(2))
		val sessions = object : SessionAccess {
			var active: ActiveSession? = null
			override fun currentSession(): ActiveSession? {
				val previous = active
				if (previous == null) active = replacement
				return previous
			}
			override fun tokenFor(session: ActiveSession): String? = "synthetic-token"
			override fun <T> withSessionLock(block: () -> T): T = synchronized(this) { block() }
		}
		val repository = SchoolRepositoryImpl(FakeGateway(), sessions, MemorySnapshotStore())
		val events = mutableListOf<RepositoryEvent>()
		repository.events().observe { events += it }

		repository.refreshSchedule(range)

		assertEquals(DataState.Loading, repository.observeSchedule(range).current)
		assertTrue(events.isEmpty())
	}

	@Test
	fun backgroundScheduleResponseAfterAccountReplacementIsDiscarded() {
		val fixture = Fixture()
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
		fixture.gateway.scheduleCall = {
			started.countDown()
			check(release.await(5, TimeUnit.SECONDS))
			GatewayReadResult.Success(ScheduleModel(9, 1))
		}
		try {
			val call = executor.submit { fixture.repository.refreshScheduleFor(session, range) }
			assertTrue(started.await(5, TimeUnit.SECONDS))
			val replacement = ActiveSession(AccountId("synthetic-other"), Generation(2))
			fixture.sessions.withSessionLock { fixture.sessions.active = replacement }
			release.countDown()
			call.get(5, TimeUnit.SECONDS)
			assertEquals(emptyMap<String, CachedSlice>(), (fixture.store.read(session) as SnapshotStoreRead.Hit).slices)
			assertEquals(emptyMap<String, CachedSlice>(), (fixture.store.read(replacement) as SnapshotStoreRead.Hit).slices)
			assertEquals(DataState.Loading, fixture.repository.observeSchedule(range).current)
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun cachedScheduleSurvivesRepositoryRecreation() {
		val fixture = Fixture()
		val model = ScheduleModel(1, 1, listOf(ScheduleDay("Monday", false, false, listOf(ScheduleLesson("id", "Math", "09:00", "12", null, null, null, null)))))
		fixture.gateway.scheduleResult = GatewayReadResult.Success(model)
		fixture.repository.refreshSchedule(range)
		fixture.gateway.scheduleResult = GatewayReadResult.Failure(AppError.Offline)
		val restarted = SchoolRepositoryImpl(fixture.gateway, fixture.sessions, fixture.store, { capturedAt })
		restarted.refreshSchedule(range)
		val state = restarted.observeSchedule(range).current as DataState.Error
		assertEquals(model, state.snapshot?.value)
		assertEquals(capturedAt, state.snapshot?.capturedAt)
	}

	@Test
	fun cachedGradesPreserveNestedSchedulesAndScoreEntries() {
		val fixture = Fixture()
		val model = GradesModel(1, listOf(GradeSubject(
			subjectName = "Math",
			finalScore = "8",
			schedules = listOf(GradeSchedule("lesson-1", "14.11.2026", "09:00", 10, "regular", "8")),
			scoreEntries = listOf(GradeScoreEntry("FO", "8", "10")),
		)))
		fixture.gateway.gradesResult = GatewayReadResult.Success(model)
		fixture.repository.refreshGrades("2")
		assertEquals(model, (fixture.repository.observeGrades("2").current as DataState.Ready).snapshot.value)
		fixture.gateway.gradesResult = GatewayReadResult.Failure(AppError.Offline)
		val restarted = SchoolRepositoryImpl(fixture.gateway, fixture.sessions, fixture.store, { capturedAt })
		restarted.refreshGrades("2")
		assertEquals(model, (restarted.observeGrades("2").current as DataState.Error).snapshot?.value)
	}

	@Test
	fun cachedOfflineDataIsStaleAndRetainsItsLastSuccessTimestamp() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(3))
		fixture.repository.refreshDiary(range)
		fixture.gateway.diaryResult = GatewayReadResult.Failure(AppError.Offline)
		fixture.repository.refreshDiary(range)
		val state = fixture.repository.observeDiary(range).current
		assertTrue(state is DataState.Error)
		assertEquals(capturedAt, (state as DataState.Error).snapshot!!.capturedAt)
		assertTrue(fixture.events.any { it == RepositoryEvent.Toast(AppError.Offline) })
	}

	@Test
	fun successfulEmptyResultWithoutCacheIsNotAnError() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Empty
		fixture.repository.refreshDiary(range)
		assertEquals(DataState.Empty, fixture.repository.observeDiary(range).current)
		assertFalse(fixture.events.any { it is RepositoryEvent.Toast })
	}

	@Test
	fun failedRefreshDoesNotReplaceCompleteSnapshot() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(4))
		fixture.repository.refreshDiary(range)
		fixture.gateway.diaryResult = GatewayReadResult.Failure(AppError.InvalidResponse)
		fixture.repository.refreshDiary(range)
		val state = fixture.repository.observeDiary(range).current as DataState.Error
		assertEquals(DiaryModel(4), state.snapshot!!.value)
	}

	@Test
	fun updatingOneSliceLeavesOtherSlicesInTheAtomicCache() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(2))
		fixture.gateway.scheduleResult = GatewayReadResult.Success(ScheduleModel(5, 2))
		fixture.repository.refreshDiary(range)
		fixture.repository.refreshSchedule(range)
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(6))
		fixture.repository.refreshDiary(range)
		assertEquals(DiaryModel(6), (fixture.repository.observeDiary(range).current as DataState.Ready).snapshot.value)
		assertEquals(ScheduleModel(5, 2), (fixture.repository.observeSchedule(range).current as DataState.Ready).snapshot.value)
		assertEquals(2, fixture.store.read(session).let { (it as SnapshotStoreRead.Hit).slices.size })
	}

	@Test
	fun timeoutEmitsErrorAndKeepsCachedValue() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(1))
		fixture.repository.refreshDiary(range)
		fixture.gateway.diaryResult = GatewayReadResult.Failure(AppError.Timeout)
		fixture.repository.refreshDiary(range)
		val state = fixture.repository.observeDiary(range).current as DataState.Error
		assertEquals(AppError.Timeout, state.error)
		assertEquals(DiaryModel(1), state.snapshot!!.value)
	}

	@Test
	fun lateResponseFromOldGenerationCannotWriteOrEmit() {
		val fixture = Fixture()
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		fixture.gateway.diaryCall = {
			started.countDown()
			release.await(2, TimeUnit.SECONDS)
			GatewayReadResult.Success(DiaryModel(99))
		}
		val worker = Thread { fixture.repository.refreshDiary(range) }
		worker.start()
		assertTrue(started.await(2, TimeUnit.SECONDS))
		fixture.sessions.active = ActiveSession(account, Generation(2))
		release.countDown()
		worker.join(2_000)
		assertTrue(fixture.store.read(ActiveSession(account, Generation(2))) is SnapshotStoreRead.Hit && (fixture.store.read(ActiveSession(account, Generation(2))) as SnapshotStoreRead.Hit).slices.isEmpty())
		assertFalse(fixture.events.any { it is RepositoryEvent.Toast })
	}

	@Test
	fun failedAtomicWriteLeavesPreviousFullCacheUntouched() {
		val fixture = Fixture()
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(2))
		fixture.gateway.scheduleResult = GatewayReadResult.Success(ScheduleModel(2, 1))
		fixture.repository.refreshDiary(range)
		fixture.repository.refreshSchedule(range)
		val before = fixture.store.read(session)
		fixture.store.failWrites = true
		fixture.gateway.diaryResult = GatewayReadResult.Success(DiaryModel(8))
		fixture.repository.refreshDiary(range)
		assertEquals(before, fixture.store.read(session))
	}

	@Test
	fun gradesDiagnosticsDistinguishSerializationAndFailedStorage() {
		val messages = mutableListOf<String>()
		val previous = DiaryDiagnostics.sink
		try {
			DiaryDiagnostics.sink = { messages.add(it) }
			val fixture = Fixture()
			fixture.store.failWrites = true
			fixture.gateway.gradesResult = GatewayReadResult.Success(GradesModel(0))
			fixture.repository.refreshGrades("private-period")
			assertTrue(messages.any { it.contains("stage=SERIALIZE outcome=SUCCESS") })
			assertTrue(messages.any { it.contains("stage=SNAPSHOT outcome=FAILURE") })
			assertTrue(messages.any { it.contains("stage=REPOSITORY outcome=FAILURE") && it.contains("error=Network") })
			assertFalse(messages.joinToString().contains("private"))
		} finally {
			DiaryDiagnostics.sink = previous
		}
	}

	private inner class Fixture {
		val sessions = FakeSessionAccess(session)
		val store = MemorySnapshotStore()
		val gateway = FakeGateway()
		val events = mutableListOf<RepositoryEvent>()
		val repository = SchoolRepositoryImpl(gateway, sessions, store, { capturedAt }).also { it.events().observe { events += it } }
	}
}

private class FakeSessionAccess(@Volatile var active: ActiveSession?) : SessionAccess {
	override fun currentSession() = active
	override fun tokenFor(session: ActiveSession): String? = if (active == session) "synthetic-token" else null
	override fun <T> withSessionLock(block: () -> T): T = synchronized(this) { block() }
}

private class FakeGateway : RepositoryGateway {
	var diaryResult: GatewayReadResult<DiaryModel> = GatewayReadResult.Failure(AppError.Offline)
	var scheduleResult: GatewayReadResult<ScheduleModel> = GatewayReadResult.Failure(AppError.Offline)
	var gradesResult: GatewayReadResult<GradesModel> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	var diaryCall: (() -> GatewayReadResult<DiaryModel>)? = null
	var scheduleCall: (() -> GatewayReadResult<ScheduleModel>)? = null
	override fun home(context: ReadContext) = GatewayReadResult.Failure<HomeModel>(AppError.UnsupportedContract)
	override fun diary(context: ReadContext) = diaryCall?.invoke() ?: diaryResult
	override fun schedule(context: ReadContext) = scheduleCall?.invoke() ?: scheduleResult
	override fun grades(context: ReadContext) = gradesResult
	override fun homeworks(context: ReadContext) = GatewayReadResult.Failure<HomeworksModel>(AppError.UnsupportedContract)
	override fun journalGrades(context: ReadContext) = GatewayReadResult.Failure<JournalGradesModel>(AppError.UnsupportedContract)
	override fun profile() = GatewayReadResult.Failure<ProfileModel>(AppError.UnsupportedContract)
	override fun logout() = GatewayReadResult.Success(Unit)
}

private class MemorySnapshotStore : SnapshotStore {
	private val values = mutableMapOf<ActiveSession, Map<String, CachedSlice>>()
	var failWrites = false
	override fun read(session: ActiveSession): SnapshotStoreRead = SnapshotStoreRead.Hit(values[session].orEmpty())
	override fun write(session: ActiveSession, slices: Map<String, CachedSlice>): Boolean {
		if (failWrites) return false
		values[session] = slices.mapValues { (_, value) -> value.copy(payload = value.payload.copyOf()) }
		return true
	}
}
