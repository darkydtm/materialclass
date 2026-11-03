package org.materialclass.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

@RunWith(AndroidJUnit4::class)
class NativeGatewayTest {
	private val loginResponse = """{"success":true,"access_token":"synthetic-token","user_info":{"userId":123,"role":"student"}}"""
	private val diaryResponse = """{"data":{"date":"01.02.2026","days":[{"day":"Monday","date":"01.02.2026","subjects":[{"label":"Math","type":"lesson","scheduleUuid":"synthetic","homeworkType":"none","homeworkBody":null,"homeworkUuid":null,"hasBooks":false,"hasFiles":true}]}]}}"""

	@Test
	fun loginAndDiaryUseObservedContractWithoutVerifiedIdentity() {
		var count = 0
		val gateway = NativeGateway(Exchange { request ->
			count++
			assertEquals("api.bilimclass.kz", request.url.host)
			if (count == 1) {
				assertEquals("POST", request.method)
				assertEquals("/api/v2/os/login", request.url.path)
				assertEquals("synthetic-user", JSONObject(String(request.body!!)).getString("login"))
				Response(200, loginResponse)
			} else {
				assertEquals("GET", request.method)
				assertEquals("/api/v4/os/clientoffice/diary", request.url.path)
				assertTrue(request.url.query.contains("schoolId=1"))
				assertTrue(request.url.query.contains("eduYear=2026"))
				assertTrue(request.url.query.contains("groupId=2"))
				assertTrue(request.url.query.contains("date=01.02.2026"))
				assertEquals("Bearer synthetic-token", request.headers["Authorization"])
				Response(200, diaryResponse)
			}
		})
		val password = "synthetic-password".toCharArray()
		val session = (gateway.login("synthetic-user", password) as GatewayResult.Success).value
		assertTrue(password.all { it == '\u0000' })
		assertFalse(session.identityVerified)
		assertEquals("123", session.identityCandidate)
		assertFalse(session.toString().contains("synthetic-token"))
		val diary = (gateway.diary(session, DiaryContext(1, 2026, 2, "01.02.2026")) as GatewayResult.Success).value
		assertEquals("Math", diary.days.single().subjects.single().label)
		assertEquals(2, count)
	}

	@Test
	fun mapsOnlyExplicitUnambiguousSchoolContext() {
		val user = """{"success":true,"access_token":"token","user_info":{"userId":123,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		val session = (NativeGateway(Exchange { Response(200, user) }).login("x", charArrayOf('x')) as GatewayResult.Success).value
		assertEquals(2026, session.context!!.eduYear)
		assertEquals(1L, session.context!!.schoolId)
		val ambiguous = user.replace("[{\"isCurrent\":true,\"eduYear\":2026}]", "[{\"isCurrent\":true,\"eduYear\":2026},{\"isCurrent\":true,\"eduYear\":2025}]")
		val other = (NativeGateway(Exchange { Response(200, ambiguous) }).login("x", charArrayOf('x')) as GatewayResult.Success).value
		assertNull(other.context)
	}

	@Test
	fun loginDoesNotTrustUnconfirmedScheduleContext() {
		val user = """{"success":true,"access_token":"token","user_info":{"userId":123,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		var calls = 0
		val gateway = NativeGateway(Exchange { calls++; Response(200, user) })
		val remote = (gateway.login("x", charArrayOf('x')) as GatewayResult.Success).value
		assertNull(remote.context!!.studentSchoolUuid)
		assertEquals(AppError.INCOMPATIBLE_RESPONSE, (gateway.schedule(VerifiedScheduleSession(remote, "123"), "01.01.2026") as GatewayResult.Failure).error)
		assertEquals(1, calls)
	}

	@Test
	fun rejectsMalformedResponsesAndUnrecognizedRoles() {
		for (response in listOf("{}", loginResponse.replace("student", "parent"), loginResponse.replace("true", "\"true\""))) {
			assertTrue(NativeGateway(Exchange { Response(200, response) }).login("x", charArrayOf('x')) is GatewayResult.Failure)
		}
		val session = UnverifiedSession("token", null, "student")
		val gateway = NativeGateway(Exchange { Response(200, diaryResponse.replace("\"hasFiles\":true", "\"hasFiles\":\"true\"")) })
		assertTrue(gateway.diary(session, DiaryContext(1, 2026, 2, "01.02.2026")) is GatewayResult.Failure)
	}

	@Test
	fun rejectsMissingOrInvalidLoginIdentity() {
		val responses = listOf(
			loginResponse.replace("\"userId\":123,", ""),
			loginResponse.replace("\"userId\":123", "\"userId\":null"),
			loginResponse.replace("\"userId\":123", "\"userId\":\"123\""),
			loginResponse.replace("\"userId\":123", "\"userId\":0"),
			loginResponse.replace("\"userId\":123", "\"userId\":-1")
		)
		responses.forEach { response ->
			val result = NativeGateway(Exchange { Response(200, response) }).login("x", charArrayOf('x'))
			assertTrue(result is GatewayResult.Failure)
		}
	}

	@Test
	fun rejectsUntrustedDestinationsBeforeConnecting() {
		for (url in listOf("http://api.bilimclass.kz/", "https://example.com/", "https://api.bilimclass.kz:444/")) {
			try {
				HttpsExchange().execute(Request(URL(url), "GET", emptyMap(), null))
				fail("Destination must be rejected")
			} catch (_: IllegalArgumentException) {
			}
		}
	}

	@Test
	fun redirectsAndNetworkErrorsNeverRetryOrClaimBadPassword() {
		var calls = 0
		val gateway = NativeGateway(Exchange { calls++; Response(302, "") })
		val result = gateway.login("x", charArrayOf('x')) as GatewayResult.Failure
		assertEquals(AppError.UNAVAILABLE, result.error)
		assertEquals(1, calls)
		val timeout = NativeGateway(Exchange { throw java.net.SocketTimeoutException() }).login("x", charArrayOf('x')) as GatewayResult.Failure
		assertEquals(AppError.TIMEOUT, timeout.error)
	}
}
