package org.materialclass.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.session.*
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class NetworkFailureRepositoryTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	private val login = """{"success":true,"access_token":"synthetic-token","user_info":{"userId":42,"role":"student","school_id":7,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
	private val profile = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
	private val session = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2))
	private val cache = object : DiaryCache {
		override fun read(session: ActiveSession): ByteArray? = null
		override fun write(session: ActiveSession, bytes: ByteArray): Boolean = true
	}

	@After
	fun cleanup() { coordinator.logout() }

	@Test
	fun verifierRetriesAmbiguousResponsesAndTransportFailures() {
		val responses = listOf(Response(200, """{"success":false}"""), Response(200, "not json")) +
			listOf(302, 401, 403, 429, 500).map { Response(it, "") }
		responses.forEach { response ->
			var calls = 0
			val verifier = NativeIdentityVerifier(Exchange { calls++; response })
			assertEquals(SessionVerification.Retry, verifier.verify(session, RequestLease()))
			assertEquals(1, calls)
		}
		listOf(SocketTimeoutException(), IOException()).forEach { error ->
			var calls = 0
			val verifier = NativeIdentityVerifier(Exchange { calls++; throw error })
			assertEquals(SessionVerification.Retry, verifier.verify(session, RequestLease()))
			assertEquals(1, calls)
		}
	}

	@Test
	fun ambiguousGatewayAndVerifierFailuresPreserveActiveSession() {
		var failure: () -> Response = { Response(200, profile) }
		val exchange = Exchange { request ->
			when (request.url.path) {
				"/api/v2/os/login" -> Response(200, login)
				"/api/v2/os/user-info" -> failure()
				else -> failure()
			}
		}
		val repository = SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, {}, store)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val epoch = repository.ownedEpoch()
		assertNotNull(epoch)
		val saved = File(context.noBackupFilesDir, "session")
		assertTrue(saved.exists())
		val cases: List<() -> Response> = listOf(
			{ Response(200, """{"success":false}""") }, { Response(200, "not json") },
			{ Response(302, "") }, { Response(401, "") }, { Response(403, "") },
			{ Response(429, "") }, { Response(500, "") },
			{ throw SocketTimeoutException() }, { throw IOException() }
		)
		cases.forEach { response ->
			failure = response
			var result: GatewayResult<String>? = null
			repository.currentWeek { result = it }
			assertTrue(result is GatewayResult.Failure)
			assertNotEquals(AppError.SESSION_EXPIRED, (result as GatewayResult.Failure).error)
			assertNotEquals(AppError.INVALID_CREDENTIALS, (result as GatewayResult.Failure).error)
			assertEquals(epoch, repository.ownedEpoch())
			assertTrue(saved.exists())
		}
		cases.forEach { response ->
			failure = response
			repository.refreshProfile(repository.beginProfileRefresh()) { _, _ -> }
			assertEquals(epoch, repository.ownedEpoch())
			assertTrue(saved.exists())
		}
	}

	@Test
	fun annualGrades401DeliversFailureAndSessionRemainsUsable() = annualGradesFailureKeepsSessionUsable(401)

	@Test
	fun annualGrades403DeliversFailureAndSessionRemainsUsable() = annualGradesFailureKeepsSessionUsable(403)

	private fun annualGradesFailureKeepsSessionUsable(status: Int) {
		var gradesStatus = status
		var gradesRequests = 0
		val exchange = Exchange { request ->
			when (request.url.path) {
				"/api/v2/os/login" -> Response(200, login)
				"/api/v2/os/user-info" -> Response(200, profile)
				"/api/v4/os/clientoffice/diary/year" -> {
					gradesRequests++
					Response(gradesStatus, if (gradesStatus == 200) """{"data":{"rows":[]}}""" else "")
				}
				else -> throw AssertionError("Unexpected path: ${request.url.path}")
			}
		}
		val repository = SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, {}, store)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val epoch = repository.ownedEpoch()
		assertNotNull(epoch)
		val deliveries = mutableListOf<AnnualGradesDelivery>()
		repository.annualGrades { deliveries.add(it) }
		assertEquals(1, deliveries.size)
		assertEquals(AppError.UNAVAILABLE, (deliveries.single().result as GatewayResult.Failure).error)
		assertTrue(repository.acceptsAnnualGrades(deliveries.single()))
		assertEquals(epoch, repository.ownedEpoch())
		assertTrue(File(context.noBackupFilesDir, "session").exists())
		gradesStatus = 200
		repository.annualGrades { deliveries.add(it) }
		assertEquals(2, deliveries.size)
		assertTrue(deliveries.last().result is GatewayResult.Success)
		assertTrue(repository.acceptsAnnualGrades(deliveries.last()))
		assertEquals(2, gradesRequests)
		assertEquals(epoch, repository.ownedEpoch())
	}

	@Test
	fun identity401RetryBlocksSchoolRequestsUntilReverified() = identityRetryFailsClosed(401)

	@Test
	fun identity403RetryBlocksSchoolRequestsUntilReverified() = identityRetryFailsClosed(403)

	private fun identityRetryFailsClosed(status: Int) {
		var identityStatus = 200
		var weekRequests = 0
		val exchange = Exchange { request ->
			when (request.url.path) {
				"/api/v2/os/login" -> Response(200, login)
				"/api/v2/os/user-info" -> Response(identityStatus, if (identityStatus == 200) profile else "")
				"/api/v4/os/clientoffice/diary/weeks" -> {
					weekRequests++
					Response(200, """{"data":{"week":"01.01.2026","weeks":[]}}""")
				}
				else -> throw AssertionError("Unexpected path: ${request.url.path}")
			}
		}
		val repository = SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, {}, store)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val epoch = repository.ownedEpoch()
		assertNotNull(epoch)
		identityStatus = status
		var profileCalls = 0
		val refreshed = repository.refreshProfile(repository.beginProfileRefresh()) { value, usable ->
			profileCalls++
			assertNull(value)
			assertFalse(usable)
		}
		assertFalse(refreshed)
		assertEquals(1, profileCalls)
		assertNull(repository.currentProfile())
		assertEquals(epoch, repository.ownedEpoch())
		var blocked: GatewayResult<String>? = null
		repository.currentWeek { blocked = it }
		assertEquals(AppError.UNAVAILABLE, (blocked as GatewayResult.Failure).error)
		assertEquals(0, weekRequests)
		identityStatus = 200
		repository.refreshProfile(repository.beginProfileRefresh()) { _, _ -> }
		var recovered: GatewayResult<String>? = null
		repository.currentWeek { recovered = it }
		assertEquals("01.01.2026", (recovered as GatewayResult.Success).value)
		assertEquals(1, weekRequests)
		assertEquals(epoch, repository.ownedEpoch())
	}
}
