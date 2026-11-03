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
	private val success = "{\"data\":{\"role\":\"student\",\"userId\":42,\"ignored\":\"synthetic\"}}"

	@After
	fun cleanup() { coordinator.logout() }

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
	fun offlineRetriesAndMissingContextFailsClosed() {
		assertEquals(SessionVerification.Retry, NativeIdentityVerifier(Exchange { throw IOException() }).verify(session))
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(session)
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
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
}
