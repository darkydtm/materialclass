package org.materialclass.data

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.After
import org.junit.Assert.*
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.materialclass.api.*
import org.materialclass.session.*
import org.materialclass.cache.EncryptedCache
import org.json.JSONObject

class ScheduleCacheTest {
	@Test
	fun scheduleExposesTimestampedOfflineSnapshot() {
		val type = runCatching { Class.forName("org.materialclass.data.ScheduleSnapshot") }.getOrNull()
		assertNotNull("Schedule must expose a timestamped snapshot", type)
	}

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	@After fun cleanup() { coordinator.logout(); EncryptedCache(context, coordinator).clearAll() }

	@Test
	fun encryptedScheduleRoundTripsOnlyForVerifiedAccountContextAndWeek() {
		var response: GatewayResult<Schedule> = GatewayResult.Success(Schedule(listOf(ScheduleDay(42, "01.01.2026", true, false, listOf(ScheduleLesson("lesson", 7, "Synthetic mathematics"))))))
		var school = 1L
		val gateway = object : TestSchoolGateway() {
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(school, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = response
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":$school,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val diaryCache = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val encryptedScheduleCache = EncryptedDiaryCache(EncryptedCache(context, coordinator, "schedule"))
		var corrupt = false
		var mismatch = false
		val scheduleCache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? {
				if (corrupt) return byteArrayOf(1, 2, 3)
				val bytes = encryptedScheduleCache.read(session) ?: return null
				if (mismatch) bytes[12] = (bytes[12].toInt() xor 1).toByte()
				return bytes
			}
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean = encryptedScheduleCache.write(session, bytes)
		}
		val repo = SchoolRepository(gateway, verifier, coordinator, diaryCache, { 1234L }, {}, store, scheduleCache)
		fun load(date: String = "01.01.2026"): GatewayResult<ScheduleSnapshot> {
			var value: GatewayResult<ScheduleSnapshot>? = null
			repo.scheduleSnapshot(date) { value = it }
			return value!!
		}
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		var week: GatewayResult<String>? = null
		repo.currentWeek { week = it }
		assertEquals("01.01.2026", (week as GatewayResult.Success).value)
		val fresh = (load() as GatewayResult.Success).value
		assertTrue(fresh.schedule.days.single().isCurrent)
		assertEquals(1234L, fresh.lastSuccessMillis); assertNull(fresh.staleError)
		response = GatewayResult.Failure(AppError.OFFLINE)
		val stale = (load() as GatewayResult.Success).value
		assertEquals(AppError.OFFLINE, stale.staleError); assertEquals(1234L, stale.lastSuccessMillis)
		assertFalse(stale.schedule.days.single().isCurrent)
		assertEquals("01.01.2026", stale.date)
		assertFalse(stale.savedPeriod)
		assertEquals("Synthetic mathematics", stale.schedule.days.single().lessons.single().themeLabel)
		assertEquals(AppError.OFFLINE, (load("08.01.2026") as GatewayResult.Failure).error)
		response = GatewayResult.Failure(AppError.TIMEOUT)
		val timedOut = (load() as GatewayResult.Success).value
		assertEquals(AppError.TIMEOUT, timedOut.staleError)
		assertFalse(timedOut.schedule.days.single().isCurrent)
		assertEquals(1234L, timedOut.lastSuccessMillis)
		assertEquals("Synthetic mathematics", timedOut.schedule.days.single().lessons.single().themeLabel)
		corrupt = true
		assertEquals(AppError.TIMEOUT, (load() as GatewayResult.Failure).error)
		corrupt = false
		mismatch = true
		assertEquals(AppError.TIMEOUT, (load() as GatewayResult.Failure).error)
		mismatch = false
		for (error in listOf(AppError.FORBIDDEN, AppError.UNAVAILABLE, AppError.INCOMPATIBLE_RESPONSE, AppError.SERVER)) {
			response = GatewayResult.Failure(error)
			assertEquals(error, (load() as GatewayResult.Failure).error)
		}
		response = GatewayResult.Success(Schedule(emptyList()))
		assertTrue((load() as GatewayResult.Success).value.schedule.days.isEmpty())
		response = GatewayResult.Failure(AppError.OFFLINE)
		assertTrue((load() as GatewayResult.Success).value.schedule.days.isEmpty())
		assertTrue(repo.login("2", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(AppError.OFFLINE, (load() as GatewayResult.Failure).error)
		repo.logout()
		var delivered = false
		repo.scheduleSnapshot("01.01.2026") { delivered = true }
		assertFalse(delivered)
	}
}
