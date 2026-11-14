package org.materialclass.notifications

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.data.SessionAccess

class NotificationLifecycleTest {
	private val session = NotificationSession(
		activeSession = ActiveSession(AccountId("synthetic-account"), Generation(4)),
		tokenPresent = true,
	)
	private var current = session.activeSession
	private val sessions = object : SessionAccess {
		override fun currentSession() = current
		override fun tokenFor(session: ActiveSession) = if (current == session) "token" else null
		override fun <T> withSessionLock(block: () -> T) = synchronized(this) { block() }
	}
	private val settings = NotificationPreferences(newGradeEnabled = true, homeworkEnabled = true)

	@Test
	fun notificationSettingsAreOffByDefaultAndPersistLocally() {
		val store = InMemoryNotificationSettingsStore()

		assertEquals(NotificationPreferences(), store.read())
		store.save(NotificationPreferences(newGradeEnabled = true, homeworkEnabled = false))

		assertEquals(NotificationPreferences(newGradeEnabled = true, homeworkEnabled = false), store.read())
	}

	@Test
	fun firstAvailableReadCreatesBaselineWithoutPublishingOldItems() {
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher, sessions)
		val result = processor.process(
			session = session,
			preferences = settings,
			permissionGranted = true,
			reads = mapOf(
				NotificationCategory.NEW_GRADE to CategoryReadResult.Available(
					listOf(NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "Changed")),
				),
			),
		)

		assertEquals(NotificationRunStatus.BASELINE, result.status)
		assertEquals(0, result.publishedCount)
		assertTrue(publisher.items.isEmpty())
	}

	@Test
	fun laterItemsArePublishedOnceAndDuplicateItemsAreSuppressed() {
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher, sessions)
		val baseline = NotificationItem(NotificationCategory.HOMEWORK, "homework-1", "Homework", "First")
		val later = NotificationItem(NotificationCategory.HOMEWORK, "homework-2", "Homework", "Second")

		processor.process(session, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(baseline))))
		val firstChange = processor.process(session, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(baseline, later))))
		val duplicateChange = processor.process(session, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(baseline, later))))

		assertEquals(NotificationRunStatus.PROCESSED, firstChange.status)
		assertEquals(1, firstChange.publishedCount)
		assertEquals(0, duplicateChange.publishedCount)
		assertEquals(listOf(later), publisher.items)
	}

	@Test
	fun offlineReadDoesNotPublishOrAdvanceBaseline() {
		val publisher = RecordingNotificationPublisher()
		val state = InMemoryNotificationStateStore()
		val processor = NotificationProcessor(state, publisher, sessions)
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "Changed")

		val offline = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Offline))
		val available = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item))))

		assertEquals(NotificationRunStatus.OFFLINE, offline.status)
		assertEquals(NotificationRunStatus.BASELINE, available.status)
		assertTrue(publisher.items.isEmpty())
	}

	@Test
	fun partialReadDoesNotCreateAnyBaseline() {
		val state = InMemoryNotificationStateStore()
		val processor = NotificationProcessor(state, RecordingNotificationPublisher(), sessions)
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "Changed")

		val result = processor.process(session, settings, true, mapOf(
			NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item)),
			NotificationCategory.HOMEWORK to CategoryReadResult.Failed,
		))

		assertEquals(NotificationRunStatus.FAILED, result.status)
		assertFalse(state.hasBaseline(session.activeSession.accountId.value, session.activeSession.generation, NotificationCategory.NEW_GRADE))
	}

	@Test
	fun failedReadIsNotReportedAsProcessed() {
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), RecordingNotificationPublisher(), sessions)

		val result = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Failed))

		assertTrue(result.status != NotificationRunStatus.PROCESSED)
	}

	@Test
	fun deniedPermissionRetainsUnpublishedItemsUntilPermissionIsGranted() {
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher, sessions)
		val baseline = NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "First")
		val later = NotificationItem(NotificationCategory.NEW_GRADE, "grade-2", "Grade", "Second")

		processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline))))
		val denied = processor.process(session, settings, false, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline, later))))
		val allowed = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline, later))))

		assertEquals(NotificationRunStatus.PERMISSION_DENIED, denied.status)
		assertEquals(1, allowed.publishedCount)
		assertEquals(listOf(later), publisher.items)
	}

	@Test
	fun publicationFailureAndRevokedPermissionRetainItemForRetry() {
		val state = InMemoryNotificationStateStore()
		var outcome = NotificationPublishResult.PERMISSION_DENIED
		val publisher = object : NotificationPublisher {
			override fun publish(item: NotificationItem) = outcome
			override fun cancelAll() = Unit
		}
		val processor = NotificationProcessor(state, publisher, sessions)
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "new", "Grade", "5")
		val reads = mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item)))
		processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(emptyList())))
		assertEquals(NotificationRunStatus.PERMISSION_DENIED, processor.process(session, settings, true, reads).status)
		outcome = NotificationPublishResult.FAILED
		assertEquals(NotificationRunStatus.FAILED, processor.process(session, settings, true, reads).status)
		assertFalse(state.hasSeen(session.activeSession.accountId.value, session.activeSession.generation, NotificationCategory.NEW_GRADE, item.stableId))
		outcome = NotificationPublishResult.PUBLISHED
		assertEquals(1, processor.process(session, settings, true, reads).publishedCount)
		assertEquals(0, processor.process(session, settings, true, reads).publishedCount)
	}

	@Test
	fun staleSessionCannotMutateStateOrPublish() {
		val state = InMemoryNotificationStateStore()
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(state, publisher, sessions)
		current = ActiveSession(AccountId("replacement"), Generation(8))
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "new", "Grade", "5")
		val result = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item))))
		assertEquals(NotificationRunStatus.STALE_GENERATION, result.status)
		assertFalse(state.hasBaseline(session.activeSession.accountId.value, session.activeSession.generation, NotificationCategory.NEW_GRADE))
		assertTrue(publisher.items.isEmpty())
	}

	@Test
	fun logoutDuringSourceReadPreventsPublicationAndStateMutation() {
		val entered = java.util.concurrent.CountDownLatch(1)
		val resume = java.util.concurrent.CountDownLatch(1)
		val publisher = RecordingNotificationPublisher()
		val state = InMemoryNotificationStateStore()
		val source = object : NotificationDataSource {
			override fun readNewGrades(session: NotificationSession): CategoryReadResult {
				entered.countDown()
				check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
				return CategoryReadResult.Available(listOf(NotificationItem(NotificationCategory.NEW_GRADE, "new", "Grade", "5")))
			}
			override fun readHomework(session: NotificationSession) = CategoryReadResult.Unsupported
		}
		val runner = NotificationWorkerRunner(source, NotificationProcessor(state, publisher, sessions), { true })
		val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit<NotificationRunResult> { runner.run(session, NotificationPreferences(newGradeEnabled = true), true) }
			assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
			sessions.withSessionLock { current = ActiveSession(AccountId("replacement"), Generation(8)) }
			resume.countDown()
			assertEquals(NotificationRunStatus.STALE_GENERATION, result.get(5, java.util.concurrent.TimeUnit.SECONDS).status)
			assertTrue(publisher.items.isEmpty())
			assertFalse(state.hasBaseline(session.activeSession.accountId.value, session.activeSession.generation, NotificationCategory.NEW_GRADE))
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun disabledSettingDuringReadPreventsPublication() {
		val entered = java.util.concurrent.CountDownLatch(1)
		val resume = java.util.concurrent.CountDownLatch(1)
		val publisher = RecordingNotificationPublisher()
		val state = InMemoryNotificationStateStore()
		val enabled = NotificationPreferences(newGradeEnabled = true)
		var currentPreferences = enabled
		var items = emptyList<NotificationItem>()
		var blockRead = false
		val source = object : NotificationDataSource {
			override fun readNewGrades(session: NotificationSession): CategoryReadResult {
				if (blockRead) {
					entered.countDown()
					check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
				}
				return CategoryReadResult.Available(items)
			}
			override fun readHomework(session: NotificationSession) = CategoryReadResult.Unsupported
		}
		val runner = NotificationWorkerRunner(source, NotificationProcessor(state, publisher, sessions), { sessions.currentSession() == it })
		assertEquals(NotificationRunStatus.BASELINE, runner.run(session, enabled, true) { currentPreferences }.status)
		items = listOf(NotificationItem(NotificationCategory.NEW_GRADE, "new", "Grade", "5"))
		blockRead = true
		val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit<NotificationRunResult> { runner.run(session, enabled, true) { currentPreferences } }
			assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
			sessions.withSessionLock { currentPreferences = NotificationPreferences() }
			resume.countDown()
			assertEquals(0, result.get(5, java.util.concurrent.TimeUnit.SECONDS).publishedCount)
			assertTrue(publisher.items.isEmpty())
			assertFalse(state.hasSeen(session.activeSession.accountId.value, session.activeSession.generation, NotificationCategory.NEW_GRADE, "new"))
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun concurrentDeliveriesArePublishedOnce() {
		val state = InMemoryNotificationStateStore()
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(state, publisher, sessions)
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "new", "Grade", "5")
		processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(emptyList())))
		val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
		val start = java.util.concurrent.CountDownLatch(1)
		try {
			val futures = (1..2).map {
				executor.submit<NotificationRunResult> {
					check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
					processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item))))
				}
			}
			start.countDown()
			assertEquals(1, futures.sumOf { it.get(5, java.util.concurrent.TimeUnit.SECONDS).publishedCount })
			assertEquals(listOf(item), publisher.items)
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun unsupportedGradeAndHomeworkSourcesReturnTypedResultWithoutTransport() {
		val calls = mutableListOf<String>()
		val source = UnsupportedNotificationDataSource

		val grades = source.readNewGrades(session).also { calls += "grades" }
		val homework = source.readHomework(session).also { calls += "homework" }

		assertEquals(CategoryReadResult.Unsupported, grades)
		assertEquals(CategoryReadResult.Unsupported, homework)
		assertEquals(listOf("grades", "homework"), calls)
	}

	@Test
	fun runnerSkipsWithoutSessionOrTokenAndDoesNotReadSources() {
		val source = RecordingNotificationDataSource()
		val runner = NotificationWorkerRunner(
			source = source,
			processor = NotificationProcessor(InMemoryNotificationStateStore(), RecordingNotificationPublisher(), sessions),
			isCurrentGeneration = { true },
		)

		assertEquals(NotificationRunStatus.NO_SESSION, runner.run(null, settings, true).status)
		assertEquals(NotificationRunStatus.NO_TOKEN, runner.run(session.copy(tokenPresent = false), settings, true).status)
		assertEquals(0, source.readCount)
	}

	@Test
	fun generationChangeCreatesNewBaselineAndOldGenerationCannotPublish() {
		val publisher = RecordingNotificationPublisher()
		val state = InMemoryNotificationStateStore()
		val processor = NotificationProcessor(state, publisher, sessions)
		val old = session
		val current = session.copy(activeSession = session.activeSession.copy(generation = Generation(5)))
		val oldItem = NotificationItem(NotificationCategory.HOMEWORK, "old", "Homework", "Old")
		val currentItem = NotificationItem(NotificationCategory.HOMEWORK, "current", "Homework", "Current")

		processor.process(old, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(oldItem))))
		this.current = current.activeSession
		val result = processor.process(current, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(currentItem))))

		assertEquals(NotificationRunStatus.BASELINE, result.status)
		assertTrue(publisher.items.isEmpty())
	}

	@Test
	fun logoutAndExpirationCancelWorkAndNotifications() {
		val scheduler = RecordingNotificationWorkScheduler()
		val publisher = RecordingNotificationPublisher()
		val lifecycle = NotificationLifecycle(scheduler, publisher)

		lifecycle.onSessionStarted(NotificationPreferences(newGradeEnabled = true, homeworkEnabled = false))
		lifecycle.onLogout()
		lifecycle.onExpiration()

		assertEquals(1, scheduler.scheduleCount)
		assertEquals(2, scheduler.cancelCount)
		assertEquals(2, publisher.cancelCount)
	}
}

private class RecordingNotificationPublisher : NotificationPublisher {
	val items = mutableListOf<NotificationItem>()
	var cancelCount = 0

	override fun publish(item: NotificationItem): NotificationPublishResult {
		items += item
		return NotificationPublishResult.PUBLISHED
	}

	override fun cancelAll() {
		cancelCount++
	}
}

private class RecordingNotificationDataSource : NotificationDataSource {
	var readCount = 0

	override fun readNewGrades(session: NotificationSession): CategoryReadResult {
		readCount++
		return CategoryReadResult.Unsupported
	}

	override fun readHomework(session: NotificationSession): CategoryReadResult {
		readCount++
		return CategoryReadResult.Unsupported
	}
}

private class RecordingNotificationWorkScheduler : NotificationWorkScheduler {
	var scheduleCount = 0
	var cancelCount = 0

	override fun schedule() {
		scheduleCount++
	}

	override fun cancel() {
		cancelCount++
	}
}
