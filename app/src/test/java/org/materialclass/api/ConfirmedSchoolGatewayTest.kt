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
		{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","exp_time":"2030-01-01T00:00:00Z","user_info":{"userId":"student-1","school_id":"school-1","group":"group-1"}}
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
	fun refreshAndTwoFactorAreExplicitlyBlockedWithoutAConfirmedContract() {
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
		assertEquals(1, sessions.clearCount)
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
	var clearCount = 0

	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession {
		active = ActiveSession(accountId, Generation(1))
		this.token = token
		return active!!
	}

	override fun invalidateAndClear() {
		active = null
		token = null
		clearCount++
	}
}
