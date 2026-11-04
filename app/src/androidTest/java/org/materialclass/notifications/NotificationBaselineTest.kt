package org.materialclass.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.materialclass.api.*
import org.materialclass.cache.EncryptedCache
import org.materialclass.security.StorageUnreadableException
import org.materialclass.session.*
import java.io.File

class NotificationBaselineTest {
	private val app = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(app)
	private val date = "04.11.2026"
	private val diaryContext = DiaryContext(1, 2026, 2, date)
	private fun diary(body: String) = Diary(date, listOf(DiaryDay("Wednesday", date, listOf(DiarySubject("Math", "lesson", "subject-1", "task", body, null, false, false)))))
	@After fun cleanup() { coordinator.logout(); EncryptedCache(app, coordinator).clearAll() }

	@Test fun firstSnapshotAndAcknowledgementAreRetrySafe() {
		val session = coordinator.activate(AccountId("synthetic-account"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, baseline.observe(session, diaryContext, diary("one")))
		assertEquals(BaselineResult.Unchanged, baseline.observe(session, diaryContext, diary("one")))
		val change = baseline.observe(session, diaryContext, diary("two")) as BaselineResult.Change
		val reopened = NotificationBaseline(app, coordinator)
		assertEquals(change, reopened.observe(session, diaryContext, diary("three")))
		val invalid = Diary(date, listOf(diary("x").days.single(), diary("x").days.single()))
		assertEquals(BaselineResult.Invalid, reopened.observe(session, diaryContext, invalid))
		assertTrue(reopened.acknowledge(session, change))
		assertFalse(reopened.acknowledge(session, change))
		assertEquals(BaselineResult.Unchanged, reopened.observe(session, diaryContext, diary("two")))
		assertTrue(reopened.observe(session, diaryContext, diary("three")) is BaselineResult.Change)
	}

	@Test fun oldAcknowledgementCannotConsumeRepeatedTransition() {
		val session = coordinator.activate(AccountId("aba"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, baseline.observe(session, diaryContext, diary("A")))
		val old = baseline.observe(session, diaryContext, diary("B")) as BaselineResult.Change
		assertTrue(baseline.acknowledge(session, old))
		val back = baseline.observe(session, diaryContext, diary("A")) as BaselineResult.Change
		assertTrue(baseline.acknowledge(session, back))
		val repeated = baseline.observe(session, diaryContext, diary("B")) as BaselineResult.Change
		assertFalse(baseline.acknowledge(session, old))
		assertTrue(baseline.acknowledge(session, repeated))
	}

	@Test fun contextAccountAndLogoutAreIsolated() {
		val first = coordinator.activate(AccountId("one"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, baseline.observe(first, diaryContext, diary("one")))
		assertEquals(BaselineResult.Initialized, baseline.observe(first, DiaryContext(1, 2026, 3, date), diary("one")))
		val change = baseline.observe(first, diaryContext, diary("two")) as BaselineResult.Change
		coordinator.logout()
		assertFalse(baseline.acknowledge(first, change))
		assertNull(baseline.observe(first, diaryContext, diary("one")))
		val second = coordinator.activate(AccountId("two"))
		assertEquals(BaselineResult.Initialized, baseline.observe(second, diaryContext, diary("two")))
	}

	@Test fun ciphertextAndCorruptionFailClosed() {
		val session = coordinator.activate(AccountId("private-identity"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, baseline.observe(session, diaryContext, diary("private-homework")))
		val directory = File(app.noBackupFilesDir, "notification-cache")
		val files = directory.listFiles()!!.filter { it.isFile }
		assertTrue(files.isNotEmpty())
		files.forEach { file ->
			val text = file.readBytes().toString(Charsets.ISO_8859_1)
			assertFalse(text.contains("private-identity")); assertFalse(text.contains("private-homework"))
		}
		val cache = EncryptedCache(app, coordinator, "notification-baseline")
		cache.write(session, byteArrayOf(0, 0, 0, 99))
		try { baseline.observe(session, diaryContext, diary("two")); fail("Corrupt baseline accepted") } catch (_: StorageUnreadableException) { }
	}
}
