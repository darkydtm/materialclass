package org.materialclass.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.json.JSONArray
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
	private fun synthetic(baseline: NotificationBaseline, request: NotificationBaseline.Request, diary: Diary, complete: Boolean = true): BaselineResult? {
		if (!complete) return BaselineResult.Invalid
		val method = NotificationBaseline::class.java.getDeclaredMethod("observeVerified", NotificationBaseline.Request::class.java, Diary::class.java)
		method.isAccessible = true
		try { return method.invoke(baseline, request, diary) as BaselineResult? }
		catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
	}
	private fun verified(session: ActiveSession, context: DiaryContext): VerifiedDiarySession {
		val remote = UnverifiedSession("synthetic", session.account.value, "student", SchoolContext(context.schoolId, context.eduYear, context.groupId))
		val profile = JSONObject().put("userId", session.account.value.toLong()).put("role", "student")
			.put("school_id", context.schoolId).put("group", JSONObject().put("id", context.groupId))
			.put("school", JSONObject().put("eduYears", JSONArray().put(JSONObject().put("eduYear", context.eduYear).put("isCurrent", true))))
		return checkNotNull(VerifiedDiarySession.compare(remote, profile))
	}
	private fun observe(baseline: NotificationBaseline, session: ActiveSession, context: DiaryContext, diary: Diary): BaselineResult? {
		val request = baseline.begin(session, verified(session, context), context) ?: return null
		return synthetic(baseline, request, diary)
	}
	private fun diary(body: String) = Diary(date, listOf(DiaryDay("Wednesday", date, listOf(DiarySubject("Math", "lesson", "subject-1", "task", body, null, false, false)))))
	@After fun cleanup() { coordinator.logout(); EncryptedCache(app, coordinator).clearAll() }

	@Test fun unverifiedSnapshotCannotInitializeBaseline() {
		val session = coordinator.activate(AccountId("unverified"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Invalid, baseline.observe(session, diaryContext, diary("unverified")))
	}

	@Test fun firstSnapshotAndAcknowledgementAreRetrySafe() {
		val session = coordinator.activate(AccountId("101"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, observe(baseline, session, diaryContext, diary("one")))
		assertEquals(BaselineResult.Unchanged, observe(baseline, session, diaryContext, diary("one")))
		val change = observe(baseline, session, diaryContext, diary("two")) as BaselineResult.Change
		val reopened = NotificationBaseline(app, coordinator)
		assertEquals(change, observe(reopened, session, diaryContext, diary("three")))
		val invalid = Diary(date, listOf(diary("x").days.single(), diary("x").days.single()))
		assertEquals(BaselineResult.Invalid, observe(reopened, session, diaryContext, invalid))
		assertTrue(reopened.acknowledge(session, change))
		assertFalse(reopened.acknowledge(session, change))
		assertEquals(BaselineResult.Unchanged, observe(reopened, session, diaryContext, diary("two")))
		assertTrue(observe(reopened, session, diaryContext, diary("three")) is BaselineResult.Change)
	}

	@Test fun oldAcknowledgementCannotConsumeRepeatedTransition() {
		val session = coordinator.activate(AccountId("102"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, observe(baseline, session, diaryContext, diary("A")))
		val old = observe(baseline, session, diaryContext, diary("B")) as BaselineResult.Change
		assertTrue(baseline.acknowledge(session, old))
		val back = observe(baseline, session, diaryContext, diary("A")) as BaselineResult.Change
		assertTrue(baseline.acknowledge(session, back))
		val repeated = observe(baseline, session, diaryContext, diary("B")) as BaselineResult.Change
		assertFalse(baseline.acknowledge(session, old))
		assertTrue(baseline.acknowledge(session, repeated))
	}

	@Test fun contextAccountAndLogoutAreIsolated() {
		val first = coordinator.activate(AccountId("103"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, observe(baseline, first, diaryContext, diary("one")))
		assertEquals(BaselineResult.Initialized, observe(baseline, first, DiaryContext(1, 2026, 3, date), diary("one")))
		val change = observe(baseline, first, diaryContext, diary("two")) as BaselineResult.Change
		coordinator.logout()
		assertFalse(baseline.acknowledge(first, change))
		assertNull(baseline.begin(first, verified(first, diaryContext), diaryContext))
		val second = coordinator.activate(AccountId("104"))
		assertEquals(BaselineResult.Initialized, observe(baseline, second, diaryContext, diary("two")))
	}

	@Test fun incompleteMismatchedAndStaleRequestsCannotChangeBaseline() {
		val session = coordinator.activate(AccountId("106"))
		val incomplete = NotificationBaseline(app, coordinator)
		val request = incomplete.begin(session, verified(session, diaryContext), diaryContext)!!
		assertEquals(BaselineResult.Invalid, synthetic(incomplete, request, diary("one"), false))
		val baseline = NotificationBaseline(app, coordinator)
		val old = baseline.begin(session, verified(session, diaryContext), diaryContext)!!
		val current = baseline.begin(session, verified(session, diaryContext), diaryContext)!!
		assertEquals(BaselineResult.Invalid, synthetic(baseline, old, diary("one")))
		assertEquals(BaselineResult.Invalid, synthetic(baseline, current, Diary("05.11.2026", emptyList())))
		assertEquals(BaselineResult.Initialized, synthetic(baseline, current, diary("one")))
		val wrong = DiaryContext(1, 2026, 3, date)
		assertNull(baseline.begin(session, verified(session, diaryContext), wrong))
		val other = coordinator.activate(AccountId("107"))
		assertNull(synthetic(baseline, current, diary("two")))
		assertNull(baseline.begin(other, verified(session, diaryContext), diaryContext))
	}

	@Test fun ciphertextAndCorruptionFailClosed() {
		val session = coordinator.activate(AccountId("105"))
		val baseline = NotificationBaseline(app, coordinator)
		assertEquals(BaselineResult.Initialized, observe(baseline, session, diaryContext, diary("private-homework")))
		val directory = File(app.noBackupFilesDir, "notification-cache")
		val files = directory.listFiles()!!.filter { it.isFile }
		assertTrue(files.isNotEmpty())
		files.forEach { file ->
			val text = file.readBytes().toString(Charsets.ISO_8859_1)
			assertFalse(text.contains("105")); assertFalse(text.contains("private-homework"))
		}
		val cache = EncryptedCache(app, coordinator, "notification-baseline")
		cache.write(session, byteArrayOf(0, 0, 0, 99))
		try { observe(baseline, session, diaryContext, diary("two")); fail("Corrupt baseline accepted") } catch (_: StorageUnreadableException) { }
	}
}
