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
		if (active != session) return false
		token = accessToken
		this.refreshToken = refreshToken
		return true
	}

	override fun invalidateAndClear() {
		active = null
		token = null
		refreshToken = null
		clearCount++
	}
}
