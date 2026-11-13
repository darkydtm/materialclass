package org.materialclass.api

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.data.ProfileModel

class ProfileSourcesTest {
	@Test
	fun profileAndStudentContextRemainUnsupportedWithoutTransportCalls() {
		val calls = mutableListOf<HttpRequestData>()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticProfileTransport { request ->
				calls += request
				HttpResponseData(500, "application/json", "{}")
			}),
			ProfileSessionController(),
		)

		assertEquals(
			GatewayReadResult.Failure<ProfileModel>(AppError.UnsupportedContract),
			gateway.profile(),
		)
		assertEquals(
			GatewayReadResult.Failure<ProfileModel>(AppError.UnsupportedContract),
			gateway.studentContext(),
		)
		assertTrue(calls.isEmpty())
	}

	@Test
	fun loginMappingPreservesConfirmedContextWithoutExposingItInDiagnostics() {
		val result = ContractMapper.mapLoginResponse(
			"""
			{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","exp_time":"2030-01-01T00:00:00Z","user_info":{"userId":"synthetic-account","school_id":"synthetic-school","group":{"id":"synthetic-group"},"studentInfo":{"studentGroupUuids":["synthetic-group-uuid"]},"school":{"eduYears":[{"eduYear":2026,"isCurrent":true}]}}}
			""".trimIndent(),
		)

		assertTrue(result is GatewayLoginResult.Success)
		val response = (result as GatewayLoginResult.Success).response
		assertEquals("synthetic-school", response.userInfo.schoolId)
		assertEquals("synthetic-group", response.userInfo.groupId)
		assertEquals("synthetic-group-uuid", response.userInfo.studentGroupUuid)
		assertEquals("2026", response.userInfo.eduYear)
		assertTrue(response.userInfo.hasStudentContext)
		assertFalse(response.toString().contains("synthetic-school"))
		assertFalse(response.toString().contains("synthetic-group"))
		assertFalse(response.toString().contains("synthetic-group-uuid"))
	}

	@Test
	fun diagnosticsContainNoAccessTokenOrContextValues() {
		val context = ReadContext(
			accessToken = "synthetic-access",
			queryParameters = mapOf("schoolId" to "synthetic-school", "eduYear" to "synthetic-year"),
		)

		assertEquals("ReadContext(queryParameterNames=[schoolId, eduYear])", context.toString())
		assertFalse(context.toString().contains("synthetic-access"))
		assertFalse(context.toString().contains("synthetic-school"))
		assertFalse(context.toString().contains("synthetic-year"))
	}
}

private class SyntheticProfileTransport(
	private val responder: (HttpRequestData) -> HttpResponseData,
) : HttpsTransport {
	override fun execute(request: HttpRequestData): HttpResponseData = responder(request)
}

private class ProfileSessionController : SessionController {
	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession =
		ActiveSession(accountId, Generation(1))

	override fun invalidateAndClear() = Unit
}
