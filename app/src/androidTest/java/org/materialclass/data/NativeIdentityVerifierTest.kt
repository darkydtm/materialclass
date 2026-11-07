package org.materialclass.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.session.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeIdentityVerifierTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	private val session = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2))
	private val success = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2,"name":"Synthetic Group"},"surname":"Synthetic Surname","firstname":"Synthetic Firstname","school":{"name":"Synthetic School","eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""

	@After
	fun cleanup() { coordinator.logout() }

	@Test
	fun profileRequiresMatchingContextAndTreatsMissingFieldsAsUnknown() {
		val named = success.replace("\"role\":\"student\"", "\"surname\":\"Synthetic Surname\",\"firstname\":\"Synthetic Firstname\",\"role\":\"student\"")
		val verified = NativeIdentityVerifier(Exchange { Response(200, named) }).verify(session) as SessionVerification.Verified
		assertEquals("Synthetic Surname", verified.profile?.surname)
		assertEquals("Synthetic Firstname", verified.profile?.firstname)
		assertNull(verified.profile?.school)
		assertNull(verified.profile?.group)
		val changed = named.replace("\"school_id\":7", "\"school_id\":8")
		assertNull((NativeIdentityVerifier(Exchange { Response(200, changed) }).verify(session) as SessionVerification.Verified).profile)
		val malformed = named.replace("\"Synthetic Surname\"", "42").replace("\"Synthetic Firstname\"", "null")
		val empty = (NativeIdentityVerifier(Exchange { Response(200, malformed) }).verify(session) as SessionVerification.Verified).profile!!
		assertNull(empty.surname)
		assertNull(empty.firstname)
	}

	@Test
	fun verifiesOnlyObservedEndpointAndStudentIdentity() {
		var calls = 0
		val verifier = NativeIdentityVerifier(Exchange { request ->
			calls++
			assertEquals("https://api.bilimclass.kz/api/v2/os/user-info", request.url.toString())
			assertEquals("GET", request.method)
			assertEquals("Bearer synthetic-token", request.headers["Authorization"])
			assertEquals("7", request.headers["x-school-id"])
			assertNull(request.body)
			Response(200, success)
		})
		val result = verifier.verify(session) as SessionVerification.Verified
		assertEquals(AccountId("42"), result.account)
		assertTrue(result.pupil)
		assertEquals(1, calls)
	}

	@Test
	fun rejectsMismatchedAndMalformedResponsesWithoutVerifying() {
		val responses = listOf(
			Response(200, "{}"), Response(200, "not json"),
			Response(200, "{\"data\":{\"role\":\"teacher\",\"userId\":42}}"),
			Response(200, "{\"data\":{\"role\":\"student\",\"userId\":43}}"),
			Response(200, "{\"data\":{\"role\":\"student\",\"userId\":\"42\"}}"),
			Response(200, "{\"data\":{\"role\":\"student\",\"userId\":42.0}}"),
			Response(200, "{\"data\":{\"role\":\"student\",\"userId\":null}}"),
			Response(401, ""), Response(403, ""), Response(302, ""), Response(500, "")
		)
		responses.forEach { response ->
			assertFalse(NativeIdentityVerifier(Exchange { response }).verify(session) is SessionVerification.Verified)
		}
	}

	@Test
	fun distinguishesContradictoryIdentityFromMalformedResponses() {
		fun verify(body: String) = NativeIdentityVerifier(Exchange { Response(200, body) }).verify(session)
		assertEquals(SessionVerification.Invalid, verify(success.replace("\"student\"", "\"teacher\"")))
		assertEquals(SessionVerification.Invalid, verify(success.replace("\"userId\":42", "\"userId\":43")))
		listOf("null", "42", "\"\"", "\"unknown-role\"", "{}", "[]").forEach { role ->
			assertEquals(SessionVerification.Retry, verify(success.replace("\"student\"", role)))
		}
		assertEquals(SessionVerification.Retry, verify(success.replace("\"userId\":42", "\"userId\":\"43\"")))
	}

	@Test
	fun offlineRetriesAndMissingContextFailsClosed() {
		assertEquals(SessionVerification.Retry, NativeIdentityVerifier(Exchange { throw IOException() }).verify(session))
		assertEquals(SessionVerification.Retry, NativeIdentityVerifier(Exchange { Response(401, "") }).verify(session))
		var calls = 0
		val verifier = NativeIdentityVerifier(Exchange { calls++; Response(200, success) })
		assertFalse(verifier.verify(UnverifiedSession("synthetic-token", "42", "student")) is SessionVerification.Verified)
		assertEquals(0, calls)
	}

	@Test
	fun missingLoginIdentityCannotBeVerified() {
		var calls = 0
		val verifier = NativeIdentityVerifier(Exchange { calls++; Response(200, success) })
		val missing = UnverifiedSession("synthetic-token", null, "student", SchoolContext(7, 2026, 2))
		assertFalse(verifier.verify(missing) is SessionVerification.Verified)
		assertEquals(0, calls)
	}

	@Test
	fun nativeVerificationRestoresMatchingAccountAndRejectsChangedAccount() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		var accesses = 0
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? { accesses++; return null }
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { accesses++; return true }
		}
		var response = success
		val verifier = NativeIdentityVerifier(Exchange { Response(200, response) })
		fun repo() = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		fun restart() {
			SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
			SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true }.setBoolean(null, true)
		}
		assertTrue(repo().login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
		val matching = repo()
		assertTrue(matching.restore() is RestoreResult.Restored)
		matching.refresh(DiaryContext(7, 2026, 2, "01.01.2026"))
		assertEquals(1, accesses)
		restart()
		response = "{\"data\":{\"role\":\"student\",\"userId\":43}}"
		val changed = repo()
		assertEquals(RestoreResult.Invalid, changed.restore())
		changed.refresh(DiaryContext(7, 2026, 2, "01.01.2026"))
		assertEquals(1, accesses)
	}

	@Test
	fun restoredSessionUsesPersistedSchoolAndCannotSurviveLogoutRace() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		var accesses = 0
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? { accesses++; return null }
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { accesses++; return true }
		}
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		var block = false
		val verifier = NativeIdentityVerifier(Exchange { request ->
			assertEquals("7", request.headers["x-school-id"])
			if (block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
			Response(200, success)
		})
		val repo = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
		SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true }.setBoolean(null, true)
		val restored = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		block = true
		var result: RestoreResult? = null
		val worker = thread { result = restored.restore() }
		try {
			check(entered.await(10, TimeUnit.SECONDS))
			restored.logout()
		} finally { release.countDown(); worker.join(10000) }
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		restored.refresh(DiaryContext(7, 2026, 2, "01.01.2026"))
		assertEquals(0, accesses)
	}

	@Test
	fun diaryRequiresMatchingProfileContextWithoutScheduleUuid() {
		var response = success
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val events = mutableListOf<DiaryState>()
		val verifier = NativeIdentityVerifier(Exchange { Response(200, response) })
		fun repo() = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, events::add, store)
		val date = "01.01.2026"
		val first = repo()
		assertTrue(first.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		first.refresh(DiaryContext(7, 2026, 2, date))
		assertEquals(1, calls)
		assertTrue(events.last() is DiaryState.Ready)
		listOf(success.replace("\"school_id\":7", "\"school_id\":8"), success.replace("\"id\":2", "\"id\":3"), success.replace("\"eduYear\":2026", "\"eduYear\":2027"), """{"data":{"role":"student","userId":42}}""").forEach {
			response = it
			val next = repo()
			assertTrue(next.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
			next.refresh(DiaryContext(7, 2026, 2, date))
			assertTrue(events.last() is DiaryState.Error)
			assertEquals(1, calls)
		}
	}

	@Test
	fun initialUuidMismatchBlocksAllSchoolGatewaysOnLoginAndRestore() {
		val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
		val candidate = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2, uuid))
		val profile = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"$uuid"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		var response = profile.replace(uuid, "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")
		var calls = 0
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(candidate)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls++; return GatewayResult.Success("01.01.2026") }
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> { calls++; return GatewayResult.Success(Schedule(emptyList())) }
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> { calls++; return GatewayResult.Success(AnnualGrades(emptyList())) }
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val events = mutableListOf<DiaryState>()
		val verifier = NativeIdentityVerifier(Exchange { Response(200, response) })
		fun repo() = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, events::add, store)
		fun exercise(repo: SchoolRepository) {
			events.clear()
			repo.refresh(DiaryContext(7, 2026, 2, "01.01.2026"))
			repo.refreshCurrent()
			repo.currentWeek {}
			repo.schedule("01.01.2026") {}
			repo.annualGrades {}
			assertEquals(0, calls)
			assertTrue(events.any { it is DiaryState.Error })
		}
		val login = repo()
		assertFalse(login.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(0, calls)
	}

	@Test
	fun malformedProfileUuidBlocksSchoolAccessAfterLoginAndRestore() {
		val response = success.replace("\"school_id\":7", "\"school_id\":7,\"studentInfo\":{\"studentSchoolUuid\":\"not-a-uuid\"}")
		var calls = 0
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls++; return GatewayResult.Success("01.01.2026") }
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> { calls++; return GatewayResult.Success(Schedule(emptyList())) }
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> { calls++; return GatewayResult.Success(AnnualGrades(emptyList())) }
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val events = mutableListOf<DiaryState>()
		val verifier = NativeIdentityVerifier(Exchange { Response(200, response) })
		fun repo() = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, events::add, store)
		fun exercise(repository: SchoolRepository) {
			events.clear()
			assertNull(repository.currentProfile())
			repository.refresh(DiaryContext(7, 2026, 2, "01.01.2026"))
			assertTrue(events.last() is DiaryState.Error)
			events.clear()
			repository.refreshCurrent()
			assertTrue(events.last() is DiaryState.Error)
			var week: GatewayResult<String>? = null
			var schedule: GatewayResult<Schedule>? = null
			var grades: AnnualGradesDelivery? = null
			repository.currentWeek { week = it }
			repository.schedule("01.01.2026") { schedule = it }
			repository.annualGrades { grades = it }
			assertTrue(week is GatewayResult.Failure)
			assertTrue(schedule is GatewayResult.Failure)
			assertTrue(grades?.result is GatewayResult.Failure)
			assertEquals(0, calls)
		}
		val login = repo()
		assertFalse(login.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(0, calls)
	}

	@Test
	fun encryptedRestoreRevalidatesScheduleCandidate() {
		val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
		val candidate = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2, uuid))
		val profile = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"$uuid"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		var response = profile
		var profiles = 0
		var schedules = 0
		val verifier = NativeIdentityVerifier(Exchange { profiles++; Response(200, response) })
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(candidate)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> { schedules++; return GatewayResult.Success(Schedule(emptyList())) }
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun repo() = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		fun restart() {
			SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
			SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true }.setBoolean(null, true)
		}
		assertTrue(repo().login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
		val restored = repo()
		assertTrue(restored.restore() is RestoreResult.Restored)
		restored.schedule("01.01.2026") {}
		assertEquals(1, schedules)
		assertEquals(2, profiles)
		restart()
		response = profile.replace("\"school_id\":7", "\"school_id\":8")
		val mismatch = repo()
		assertTrue(mismatch.restore() is RestoreResult.Invalid)
		mismatch.schedule("01.01.2026") {}
		assertEquals(1, schedules)
		assertEquals(2, profiles)
	}
}
