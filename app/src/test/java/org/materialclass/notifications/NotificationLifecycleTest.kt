package org.materialclass.notifications

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation

class NotificationLifecycleTest {
	private val session = NotificationSession(
		activeSession = ActiveSession(AccountId("synthetic-account"), Generation(4)),
		tokenPresent = true,
	)
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
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher)
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
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher)
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
		val processor = NotificationProcessor(state, publisher)
		val item = NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "Changed")

		val offline = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Offline))
		val available = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(item))))

		assertEquals(NotificationRunStatus.OFFLINE, offline.status)
		assertEquals(NotificationRunStatus.BASELINE, available.status)
		assertTrue(publisher.items.isEmpty())
	}

	@Test
	fun deniedPermissionSuppressesPublishingButStillDeduplicatesObservedItems() {
		val publisher = RecordingNotificationPublisher()
		val processor = NotificationProcessor(InMemoryNotificationStateStore(), publisher)
		val baseline = NotificationItem(NotificationCategory.NEW_GRADE, "grade-1", "Grade", "First")
		val later = NotificationItem(NotificationCategory.NEW_GRADE, "grade-2", "Grade", "Second")

		processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline))))
		val denied = processor.process(session, settings, false, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline, later))))
		val allowed = processor.process(session, settings, true, mapOf(NotificationCategory.NEW_GRADE to CategoryReadResult.Available(listOf(baseline, later))))

		assertEquals(NotificationRunStatus.PERMISSION_DENIED, denied.status)
		assertEquals(0, allowed.publishedCount)
		assertTrue(publisher.items.isEmpty())
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
			processor = NotificationProcessor(InMemoryNotificationStateStore(), RecordingNotificationPublisher()),
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
		val processor = NotificationProcessor(state, publisher)
		val old = session
		val current = session.copy(activeSession = session.activeSession.copy(generation = Generation(5)))
		val oldItem = NotificationItem(NotificationCategory.HOMEWORK, "old", "Homework", "Old")
		val currentItem = NotificationItem(NotificationCategory.HOMEWORK, "current", "Homework", "Current")

		processor.process(old, settings, true, mapOf(NotificationCategory.HOMEWORK to CategoryReadResult.Available(listOf(oldItem))))
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

	override fun publish(item: NotificationItem) {
		items += item
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
