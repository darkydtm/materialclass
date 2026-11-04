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
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(school, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = response
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":$school,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), null)
		}
		val diaryCache = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val scheduleCache = EncryptedDiaryCache(EncryptedCache(context, coordinator, "schedule"))
		val repo = SchoolRepository(gateway, verifier, coordinator, diaryCache, { 1234L }, {}, store, scheduleCache)
		fun load(date: String = "01.01.2026"): GatewayResult<ScheduleSnapshot> {
			var value: GatewayResult<ScheduleSnapshot>? = null
			repo.scheduleSnapshot(date) { value = it }
			return value!!
		}
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val fresh = (load() as GatewayResult.Success).value
		assertEquals(1234L, fresh.lastSuccessMillis); assertNull(fresh.staleError)
		response = GatewayResult.Failure(AppError.OFFLINE)
		val stale = (load() as GatewayResult.Success).value
		assertEquals(AppError.OFFLINE, stale.staleError); assertEquals(1234L, stale.lastSuccessMillis)
		assertEquals("Synthetic mathematics", stale.schedule.days.single().lessons.single().themeLabel)
		assertEquals(AppError.OFFLINE, (load("08.01.2026") as GatewayResult.Failure).error)
		response = GatewayResult.Failure(AppError.TIMEOUT)
		assertEquals(AppError.TIMEOUT, (load() as GatewayResult.Success).value.staleError)
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
