package org.materialclass.api

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation

class ConfirmedSchoolGatewayTest {
	private val credentials = LoginCredentials("synthetic-login", "synthetic-password")
	private val validBody = """
		{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","exp_time":"2030-01-01T00:00:00Z","user_info":{"userId":"student-1","school_id":"school-1","group":{"id":"group-1"},"studentInfo":{"studentGroupUuids":["group-uuid-1"]},"school":{"eduYears":[{"eduYear":2026,"isCurrent":true}]}}}
	""".trimIndent()

	@Test
	fun loginUsesOnlyTheConfirmedHttpsContractAndDoesNotLogSecrets() {
		val transport = SyntheticHttpsTransport { HttpResponseData(200, "application/json", validBody) }
		val logs = mutableListOf<String>()
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(transport, logs::add),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)

		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		assertEquals("POST", transport.lastRequest!!.method)
		assertEquals("https://api.bilimclass.kz/api/v2/os/login", transport.lastRequest!!.url)
		assertEquals("application/json", transport.lastRequest!!.headers["Accept"])
		assertEquals("ru", transport.lastRequest!!.headers["X-Localization"])
		assertTrue(transport.lastRequest!!.headers["X-Mathrix"].orEmpty().isNotBlank())
		assertEquals("https://www.bilimclass.kz", transport.lastRequest!!.headers["Origin"])
		assertEquals("https://www.bilimclass.kz/", transport.lastRequest!!.headers["Referer"])
		assertTrue(transport.lastRequest!!.body.contains("\"login\":\"synthetic-login\""))
		assertTrue(transport.lastRequest!!.body.contains("\"password\":\"synthetic-password\""))
		assertTrue(logs.none { it.contains("synthetic-password") || it.contains("synthetic-access") || it.contains("synthetic-refresh") })
	}

	@Test
	fun blockedDefaultIdentityPathCannotActivateTheSession() {
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { HttpResponseData(200, "application/json", validBody) }),
			sessions,
		)

		assertEquals(LoginResult.Failure(AppError.IdentityNotConfirmed), gateway.login(credentials))
		assertNull(sessions.active)
	}

	@Test
	fun unconfirmedIdentityNeverActivatesTheSession() {
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { HttpResponseData(200, "application/json", validBody) }),
			sessions,
			ContractBlockedIdentityVerifier,
		)

		val result = gateway.login(credentials)

		assertEquals(LoginResult.Failure(AppError.IdentityNotConfirmed), result)
		assertNull(sessions.active)
		assertEquals(1, sessions.clearCount)
	}


	@Test
	fun confirmedStudentIdentityActivatesSessionOnlyAfterVerification() {
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { HttpResponseData(200, "application/json", validBody) }),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)

		val result = gateway.login(credentials)

		assertTrue(result is LoginResult.Confirmed)
		assertEquals(AccountId("student-1"), sessions.active!!.accountId)
		assertEquals("synthetic-access", sessions.token)
	}

	@Test
	fun documentedLoginContextBuildsReadQueriesAndProfile() {
		val body = """
			{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","exp_time":"2030-01-01T00:00:00Z","user_info":{"userId":"student-1","school_id":"school-1","group":{"id":"group-1","name":"Class"},"studentInfo":{"studentSchoolUuid":"student-school-1","studentGroupUuids":["group-uuid-1"]},"school":{"name":"School","region":"Region","eduYears":[{"eduYear":2026,"isCurrent":true}]}}}
		""".trimIndent()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { HttpResponseData(200, "application/json", body) }),
			FakeSessionController(),
			StudentRoleVerifier,
		)

		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		val context = gateway.readContext("synthetic-access", "schedule:2026-09-07:2026-09-13")
		assertEquals("school-1", context.queryParameters["schoolId"])
		assertEquals("2026", context.queryParameters["eduYear"])
		assertEquals("07.09.2026", context.queryParameters["date"])
		assertEquals("student-school-1", context.queryParameters["studentSchoolUuid"])
		listOf("1", "2", "3", "4").forEach { period ->
			val grades = gateway.readContext("synthetic-access", "grades:$period")
			assertEquals(period, grades.queryParameters["period"])
			assertEquals("quarter", grades.queryParameters["periodType"])
		}
		listOf("2026-09-14" to "14.09.2026", "2026-10-05" to "05.10.2026").forEach { (date, expected) ->
			val schedule = gateway.readContext("synthetic-access", "schedule:$date:$date")
			assertEquals(expected, schedule.queryParameters["date"])
		}
		assertTrue(gateway.profile() is GatewayReadResult.Success)
	}

	@Test
	fun disallowedRoleClearsAnyExistingSessionAndDoesNotActivateNewOne() {
		val sessions = FakeSessionController().also { it.active = ActiveSession(AccountId("old"), Generation(3)) }
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { HttpResponseData(200, "application/json", validBody) }),
			sessions,
			DisallowedRoleVerifier,
		)

		assertEquals(LoginResult.Failure(AppError.DisallowedRole), gateway.login(credentials))
		assertNull(sessions.active)
		assertEquals(1, sessions.clearCount)
	}

	@Test
	fun invalidCredentialsTimeoutOfflineMalformedAndTlsAreTyped() {
		assertEquals(LoginResult.Failure(AppError.InvalidCredentials), loginWith(HttpResponseData(401, "application/json", "{}")))
		assertEquals(LoginResult.Failure(AppError.Timeout), loginWithFailure(TransportFailure.Timeout))
		assertEquals(LoginResult.Failure(AppError.Offline), loginWithFailure(TransportFailure.Offline))
		assertEquals(LoginResult.Failure(AppError.TlsFailure), loginWithFailure(TransportFailure.Tls))
		assertEquals(LoginResult.Failure(AppError.InvalidResponse), loginWith(HttpResponseData(200, "application/json", "not-json")))
	}

	@Test
	fun nonJsonSuccessAndMissingRequiredAuthFieldsCannotActivate() {
		assertEquals(LoginResult.Failure(AppError.InvalidResponse), loginWith(HttpResponseData(200, "text/html", validBody)))
		assertEquals(LoginResult.Failure(AppError.InvalidResponse), loginWith(HttpResponseData(200, "application/json", "{\"access_token\":\"a\",\"user_info\":{}}")))
	}

	@Test
	fun refreshRequiresAnActiveSessionAndTwoFactorRemainsUnsupported() {
		val transportCalls = AtomicInteger()
		val transport = SyntheticHttpsTransport { transportCalls.incrementAndGet(); HttpResponseData(500, "", "") }
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(transport),
			sessions,
			ContractBlockedIdentityVerifier,
		)

		assertEquals(RefreshResult.LoginRequired, gateway.refresh())
		assertEquals(TwoFactorResult.Unsupported, gateway.startTwoFactor())
		assertEquals(0, transportCalls.get())
		assertEquals(0, sessions.clearCount)
	}

	@Test
	fun staleRefreshFailureDoesNotClearAReplacementSession() {
		val sessions = FakeSessionController()
		val transport = SyntheticHttpsTransport {
			HttpResponseData(401, "application/json", "{}")
		}
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(transport),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		val replacement = ActiveSession(AccountId("replacement"), Generation(2))
		sessions.active = replacement
		sessions.token = "replacement-access"
		sessions.refreshToken = "replacement-refresh"

		assertEquals(RefreshResult.LoginRequired, gateway.refresh())
		assertEquals(replacement, sessions.active)
		assertEquals("replacement-access", sessions.token)
		assertEquals("replacement-refresh", sessions.refreshToken)
		assertEquals(0, sessions.clearCount)
	}

	@Test
	fun staleRefreshSuccessCannotOverwriteRotatedTokens() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", validBody)
		responses += HttpResponseData(200, "application/json", "{\"access_token\":\"stale-access\",\"refresh_token\":\"stale-refresh\",\"exp_time\":\"2030-01-01T00:00:00Z\"}")
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { responses.removeFirst() }),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		sessions.refreshToken = "already-rotated"

		assertEquals(RefreshResult.LoginRequired, gateway.refresh())
		assertEquals("synthetic-access", sessions.token)
		assertEquals("already-rotated", sessions.refreshToken)
		assertEquals(0, sessions.clearCount)
	}

	@Test
	fun loginRequiredRefreshInvalidatesOnlyTheMatchingSessionAndToken() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", validBody)
		responses += HttpResponseData(401, "application/json", "{}")
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { responses.removeFirst() }),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		val originalSession = sessions.active!!
		sessions.onRefreshResponse = {
			sessions.active = ActiveSession(AccountId("replacement"), Generation(2))
			sessions.token = "replacement-access"
			sessions.refreshToken = "replacement-refresh"
		}

		assertEquals(RefreshResult.LoginRequired, gateway.refresh())
		assertEquals("replacement", sessions.active?.accountId?.value)
		assertEquals("replacement-access", sessions.token)
		assertEquals("replacement-refresh", sessions.refreshToken)
		assertEquals(0, sessions.clearCount)
		assertTrue(originalSession != sessions.active)
	}

	@Test
	fun transientRefreshFailureKeepsActiveSession() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", validBody)
		responses += HttpResponseData(500, "application/json", "{}")
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticHttpsTransport { responses.removeFirst() }),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		val session = sessions.active

		assertEquals(RefreshResult.Failed(AppError.HttpStatus(500)), gateway.refresh())
		assertEquals(session, sessions.active)
		assertEquals("synthetic-refresh", sessions.refreshToken)
		assertEquals(0, sessions.clearCount)
	}

	@Test
	fun concurrentRefreshCallsReuseTheRotatedTokenInsteadOfSendingItTwice() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", validBody)
		responses += HttpResponseData(200, "application/json", "{\"access_token\":\"next-access\",\"refresh_token\":\"next-refresh\",\"exp_time\":\"2030-01-01T00:00:00Z\"}")
		val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
		val transport = SyntheticHttpsTransport { request ->
			requests += request
			if (request.url.endsWith("/login")) responses.removeFirst()
			else {
				Thread.sleep(25)
				responses.removeFirst()
			}
		}
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(transport),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		val start = java.util.concurrent.CountDownLatch(1)
		val results = java.util.Collections.synchronizedList(mutableListOf<RefreshResult>())
		val callers = List(2) {
			Thread {
				start.await()
				results += gateway.refresh()
			}.also(Thread::start)
		}
		start.countDown()
		callers.forEach(Thread::join)

		val refreshRequests = requests.filter { it.url.endsWith("/refresh_token") }
		assertEquals(1, refreshRequests.size)
		assertEquals(2, results.count { it is RefreshResult.Refreshed })
		assertEquals("next-access", sessions.token)
		assertEquals("next-refresh", sessions.refreshToken)
	}

	@Test
	fun confirmedSessionRotatesRefreshTokens() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", validBody)
		responses += HttpResponseData(200, "application/json", "{\"access_token\":\"next-access\",\"refresh_token\":\"next-refresh\",\"exp_time\":\"2030-01-01T00:00:00Z\"}")
		val transport = SyntheticHttpsTransport { responses.removeFirst() }
		val sessions = FakeSessionController()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(transport),
			sessions,
			SyntheticConfirmedStudentVerifier(AccountId("student-1")),
		)
		assertTrue(gateway.login(credentials) is LoginResult.Confirmed)
		assertEquals(RefreshResult.Refreshed(sessions.active!!), gateway.refresh())
		assertEquals("next-access", sessions.token)
		assertEquals("next-refresh", sessions.refreshToken)
	}

	private fun loginWith(response: HttpResponseData): LoginResult = ConfirmedSchoolGateway(
		SchoolGateway(SyntheticHttpsTransport { response }),
		FakeSessionController(),
		SyntheticConfirmedStudentVerifier(AccountId("student-1")),
	).login(credentials)

	private fun loginWithFailure(failure: TransportFailure): LoginResult = ConfirmedSchoolGateway(
		SchoolGateway(SyntheticHttpsTransport { throw failure }),
		FakeSessionController(),
		SyntheticConfirmedStudentVerifier(AccountId("student-1")),
	).login(credentials)
}

private class SyntheticHttpsTransport(
	private val responder: (HttpRequestData) -> HttpResponseData,
) : HttpsTransport {
	var lastRequest: HttpRequestData? = null

	override fun execute(request: HttpRequestData): HttpResponseData {
		lastRequest = request
		assertTrue(request.url.startsWith("https://"))
		return responder(request)
	}
}

private class FakeSessionController : SessionController {
	var active: ActiveSession? = null
	var token: String? = null
	var refreshToken: String? = null
	var clearCount = 0
	var onRefreshResponse: (() -> Unit)? = null

	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession {
		active = ActiveSession(accountId, Generation(1))
		this.token = token
		return active!!
	}

	override fun setRefreshToken(session: ActiveSession, refreshToken: String): Boolean {
		if (active != session) return false
		this.refreshToken = refreshToken
		return true
	}

	override fun currentSession(): ActiveSession? = active

	override fun accessTokenFor(session: ActiveSession): String? = token.takeIf { active == session }

	override fun refreshTokenFor(session: ActiveSession): String? = refreshToken.takeIf { active == session }

	override fun replaceTokens(session: ActiveSession, accessToken: String, refreshToken: String, expiresAt: Instant): Boolean {
		onRefreshResponse?.invoke()
		onRefreshResponse = null
		if (active != session) return false
		token = accessToken
		this.refreshToken = refreshToken
		return true
	}

	override fun replaceTokensIfCurrent(
		session: ActiveSession,
		expectedRefreshToken: String,
		accessToken: String,
		refreshToken: String,
		expiresAt: Instant,
	): Boolean {
		if (active != session || this.refreshToken != expectedRefreshToken) return false
		return replaceTokens(session, accessToken, refreshToken, expiresAt)
	}

	override fun invalidateIfCurrent(session: ActiveSession, expectedRefreshToken: String): Boolean {
		onRefreshResponse?.invoke()
		onRefreshResponse = null
		if (active != session || refreshToken != expectedRefreshToken) return false
		invalidateAndClear()
		return true
	}

	override fun invalidateAndClear() {
		active = null
		token = null
		refreshToken = null
		clearCount++
	}
}
