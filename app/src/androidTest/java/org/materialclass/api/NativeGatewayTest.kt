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
	fun rejectsMalformedResponsesAndUnrecognizedRoles() {
		for (response in listOf("{}", loginResponse.replace("student", "parent"), loginResponse.replace("true", "\"true\""))) {
			assertTrue(NativeGateway(Exchange { Response(200, response) }).login("x", charArrayOf('x')) is GatewayResult.Failure)
		}
		val session = UnverifiedSession("token", null, "student")
		val gateway = NativeGateway(Exchange { Response(200, diaryResponse.replace("\"hasFiles\":true", "\"hasFiles\":\"true\"")) })
		assertTrue(gateway.diary(session, DiaryContext(1, 2026, 2, "01.02.2026")) is GatewayResult.Failure)
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
