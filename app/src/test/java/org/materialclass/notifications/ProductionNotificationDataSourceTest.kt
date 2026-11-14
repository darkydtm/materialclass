package org.materialclass.notifications

import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.api.AppError
import org.materialclass.api.GatewayReadResult
import org.materialclass.api.ReadContext
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.DateRange
import org.materialclass.data.Generation
import org.materialclass.data.HomeworkItem
import org.materialclass.data.HomeworksModel
import org.materialclass.data.JournalGrade
import org.materialclass.data.JournalGradesModel
import org.materialclass.data.SessionAccess

class ProductionNotificationDataSourceTest {
	private val active = ActiveSession(AccountId("synthetic"), Generation(1))
	private val session = NotificationSession(active, true)
	private val range = DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"))
	private var current: ActiveSession? = active
	private val sessions = object : SessionAccess {
		override fun currentSession() = current
		override fun tokenFor(session: ActiveSession) = if (current == session) "synthetic-token" else null
		override fun <T> withSessionLock(block: () -> T) = block()
	}
	private var grades: GatewayReadResult<JournalGradesModel> = GatewayReadResult.Empty
	private var homework: GatewayReadResult<HomeworksModel> = GatewayReadResult.Empty
	private val keys = mutableListOf<String>()
	private val source = ProductionNotificationDataSource(sessions, { token, key ->
		keys += key
		ReadContext(token)
	}, { range }, { grades }, { homework })

	private fun grade(id: String?, mark: String?) = JournalGrade(id, null, "not-the-mark", null, null, null, mark)
	private fun task(id: String?) = HomeworkItem(id, "Synthetic subject", "Synthetic theme", "Synthetic body", null, false)
	private fun available(result: CategoryReadResult) = (result as CategoryReadResult.Available).items

	@Test
	fun missingPeriodFailsWithoutCreatingBaselineOrCallingJournal() {
		var journalCalled = false
		val missing = ProductionNotificationDataSource(sessions, { token, _ -> ReadContext(token) }, { null }, {
			journalCalled = true
			GatewayReadResult.Empty
		}, { GatewayReadResult.Empty })
		val state = InMemoryNotificationStateStore()
		val processor = NotificationProcessor(state, object : NotificationPublisher {
			override fun publish(item: NotificationItem) = NotificationPublishResult.PUBLISHED
			override fun cancelAll() = Unit
		}, sessions)
		val result = missing.readNewGrades(session)
		assertEquals(NotificationRunStatus.FAILED, processor.process(session, NotificationPreferences(newGradeEnabled = true), true, mapOf(NotificationCategory.NEW_GRADE to result)).status)
		assertFalse(journalCalled)
		assertFalse(state.hasBaseline(active.accountId.value, active.generation, NotificationCategory.NEW_GRADE))
	}

	@Test
	fun productionSourceImplementsNotificationContract() {
		assertTrue(source is NotificationDataSource)
	}

	@Test
	fun successfulReadsMapActualMarksAndHomeworkWithStableIdentities() {
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("lesson", "5"), grade("lesson", "4")), 1, 0))
		homework = GatewayReadResult.Success(HomeworksModel(listOf(task("homework"))))
		val marks = available(source.readNewGrades(session))
		val tasks = available(source.readHomework(session))
		assertEquals(listOf("5", "4"), marks.map { it.body })
		assertEquals(2, marks.map { it.stableId }.distinct().size)
		assertTrue(marks.all { it.category == NotificationCategory.NEW_GRADE })
		assertEquals("homework", tasks.single().stableId)
		assertEquals(NotificationCategory.HOMEWORK, tasks.single().category)
		assertEquals(listOf("journal:2026-01-01:2026-03-31", "homeworks"), keys)
		assertEquals(marks, available(source.readNewGrades(session)))
	}

	@Test
	fun emptyAndMalformedIdentifiersAreExcludedAndDuplicatesAreCollapsed() {
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade(null, "5"), grade(" ", "5"), grade("x", null), grade("x", " "), grade("x", "0"), grade("x", "0")), 1, 0))
		homework = GatewayReadResult.Success(HomeworksModel(listOf(task(null), task(" "), task("id"), task("id"))))
		assertEquals(listOf("0"), available(source.readNewGrades(session)).map { it.body })
		assertEquals(listOf("id"), available(source.readHomework(session)).map { it.stableId })
		grades = GatewayReadResult.Empty
		homework = GatewayReadResult.Empty
		assertTrue(available(source.readNewGrades(session)).isEmpty())
		assertTrue(available(source.readHomework(session)).isEmpty())
	}

	@Test
	fun failureStatusesAndPartialGroupsNeverEstablishGradeBaseline() {
		val state = InMemoryNotificationStateStore()
		val publisher = object : NotificationPublisher {
			override fun publish(item: NotificationItem) = NotificationPublishResult.PUBLISHED
			override fun cancelAll() = Unit
		}
		val processor = NotificationProcessor(state, publisher, sessions)
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("x", "5")), 1, 1))
		val partial = source.readNewGrades(session)
		assertEquals(CategoryReadResult.Failed, partial)
		processor.process(session, NotificationPreferences(newGradeEnabled = true), true, mapOf(NotificationCategory.NEW_GRADE to partial))
		assertFalse(state.hasBaseline(active.accountId.value, active.generation, NotificationCategory.NEW_GRADE))
		grades = GatewayReadResult.Failure(AppError.Offline)
		homework = GatewayReadResult.Failure(AppError.UnsupportedContract)
		assertEquals(CategoryReadResult.Offline, source.readNewGrades(session))
		assertEquals(CategoryReadResult.Unsupported, source.readHomework(session))
		grades = GatewayReadResult.Failure(AppError.InvalidResponse)
		assertEquals(CategoryReadResult.Failed, source.readNewGrades(session))
	}

	@Test
	fun permissionDoesNotAffectSourceReadsAndStaleSessionFailsClosed() {
		val runner = NotificationWorkerRunner(source, NotificationProcessor(InMemoryNotificationStateStore(), object : NotificationPublisher {
			override fun publish(item: NotificationItem) = NotificationPublishResult.PUBLISHED
			override fun cancelAll() = Unit
		}, sessions), { true })
		val result = runner.run(session, NotificationPreferences(homeworkEnabled = true), false)
		assertEquals(NotificationRunStatus.BASELINE, result.status)
		assertEquals(listOf("homeworks"), keys)
		current = null
		assertEquals(CategoryReadResult.Failed, source.readHomework(session))
	}

	@Test
	fun syntheticGatewayFeedsRunnerBaselineAndNewItems() {
		val published = mutableListOf<NotificationItem>()
		val runner = NotificationWorkerRunner(source, NotificationProcessor(InMemoryNotificationStateStore(), object : NotificationPublisher {
			override fun publish(item: NotificationItem): NotificationPublishResult {
				published += item
				return NotificationPublishResult.PUBLISHED
			}
			override fun cancelAll() = Unit
		}, sessions), { sessions.currentSession() == it })
		val enabled = NotificationPreferences(newGradeEnabled = true, homeworkEnabled = true)
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("old", "5")), 1, 0))
		homework = GatewayReadResult.Success(HomeworksModel(listOf(task("old"))))
		assertEquals(NotificationRunStatus.BASELINE, runner.run(session, enabled, true).status)
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("old", "5"), grade("new", "4")), 1, 0))
		homework = GatewayReadResult.Success(HomeworksModel(listOf(task("old"), task("new"))))
		assertEquals(NotificationRunStatus.PERMISSION_DENIED, runner.run(session, enabled, false).status)
		assertEquals(2, runner.run(session, enabled, true).publishedCount)
		assertEquals(0, runner.run(session, enabled, true).publishedCount)
		assertEquals(setOf(NotificationCategory.NEW_GRADE, NotificationCategory.HOMEWORK), published.map { it.category }.toSet())
		grades = GatewayReadResult.Failure(AppError.Offline)
		assertEquals(NotificationRunStatus.OFFLINE, runner.run(session, enabled, true).status)
	}

	@Test
	fun switchingPeriodStartsIndependentBaseline() {
		var selected = range
		val other = DateRange(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"))
		val source = ProductionNotificationDataSource(sessions, { token, _ -> ReadContext(token) }, { selected }, { grades }, { homework })
		val published = mutableListOf<NotificationItem>()
		val runner = NotificationWorkerRunner(source, NotificationProcessor(InMemoryNotificationStateStore(), object : NotificationPublisher {
			override fun publish(item: NotificationItem): NotificationPublishResult {
				published += item
				return NotificationPublishResult.PUBLISHED
			}
			override fun cancelAll() = Unit
		}, sessions), { sessions.currentSession() == it })
		val enabled = NotificationPreferences(newGradeEnabled = true)
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("first", "5")), 1, 0))
		assertEquals(NotificationRunStatus.BASELINE, runner.run(session, enabled, true).status)
		selected = other
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("second", "4")), 1, 0))
		assertEquals(NotificationRunStatus.BASELINE, runner.run(session, enabled, true).status)
		assertTrue(published.isEmpty())
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("second", "4"), grade("new-second", "5")), 1, 0))
		assertEquals(1, runner.run(session, enabled, true).publishedCount)
		selected = range
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("first", "5"), grade("new-first", "4")), 1, 0))
		assertEquals(1, runner.run(session, enabled, true).publishedCount)
		assertEquals(2, published.size)
	}

	@Test
	fun periodBaselinesRemainIsolatedAcrossAccountsAndGenerations() {
		val published = mutableListOf<NotificationItem>()
		val runner = NotificationWorkerRunner(source, NotificationProcessor(InMemoryNotificationStateStore(), object : NotificationPublisher {
			override fun publish(item: NotificationItem): NotificationPublishResult {
				published += item
				return NotificationPublishResult.PUBLISHED
			}
			override fun cancelAll() = Unit
		}, sessions), { sessions.currentSession() == it })
		val enabled = NotificationPreferences(newGradeEnabled = true)
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("first", "5")), 1, 0))
		assertEquals(NotificationRunStatus.BASELINE, runner.run(session, enabled, true).status)
		val replacement = ActiveSession(AccountId("other-synthetic"), Generation(1))
		current = replacement
		grades = GatewayReadResult.Success(JournalGradesModel(listOf(grade("second", "4")), 1, 0))
		assertEquals(NotificationRunStatus.BASELINE, runner.run(NotificationSession(replacement, true), enabled, true).status)
		val renewed = active.copy(generation = Generation(2))
		current = renewed
		assertEquals(NotificationRunStatus.BASELINE, runner.run(NotificationSession(renewed, true), enabled, true).status)
		assertTrue(published.isEmpty())
	}

	@Test
	fun periodChangesDuringJournalReadDiscardResultsEvenAfterSwitchBack() {
		val lock = Any()
		var epoch = 0
		var selected = range
		val other = DateRange(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"))
		val localSessions = object : SessionAccess {
			override fun currentSession() = synchronized(lock) { current }
			override fun tokenFor(session: ActiveSession) = synchronized(lock) { if (current == session) "synthetic-token" else null }
			override fun <T> withSessionLock(block: () -> T) = synchronized(lock) { block() }
		}
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val source = ProductionNotificationDataSource(localSessions, { token, _ -> ReadContext(token) }, { synchronized(lock) { selected } }, {
			entered.countDown()
			check(release.await(5, TimeUnit.SECONDS))
			GatewayReadResult.Success(JournalGradesModel(listOf(grade("old", "5")), 1, 0))
		}, { GatewayReadResult.Empty })
		val published = mutableListOf<NotificationItem>()
		val runner = NotificationWorkerRunner(source, NotificationProcessor(InMemoryNotificationStateStore(), object : NotificationPublisher {
			override fun publish(item: NotificationItem): NotificationPublishResult {
				published += item
				return NotificationPublishResult.PUBLISHED
			}
			override fun cancelAll() = Unit
		}, localSessions), { localSessions.currentSession() == it }, { synchronized(lock) { epoch to selected } })
		val executor = Executors.newSingleThreadExecutor()
		try {
			val future = executor.submit<NotificationRunResult> { runner.run(session, NotificationPreferences(newGradeEnabled = true), true) }
			assertTrue(entered.await(5, TimeUnit.SECONDS))
			synchronized(lock) { selected = other; epoch++; selected = range; epoch++ }
			release.countDown()
			assertEquals(NotificationRunStatus.STALE_GENERATION, future.get(5, TimeUnit.SECONDS).status)
			assertTrue(published.isEmpty())
			assertEquals(NotificationRunStatus.BASELINE, runner.run(session, NotificationPreferences(newGradeEnabled = true), true).status)
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun unexpectedSourceFailureDoesNotExposeDataOrReportSuccess() {
		val broken = ProductionNotificationDataSource(sessions, { _, _ -> throw IllegalStateException("synthetic private data") }, { range }, { grades }, { homework })
		assertEquals(CategoryReadResult.Failed, broken.readNewGrades(session))
		assertEquals(CategoryReadResult.Failed, broken.readHomework(session))
	}
}
