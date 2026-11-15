package org.materialclass.api

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeComparisonTest {
	@Test
	fun requestsAndParsesObservedComparison() {
		var request: HttpRequestData? = null
		val gateway = SchoolGateway(HttpsTransport {
			request = it
			HttpResponseData(200, "application/json", """{"maxScore":10,"studentMark":{"score":8,"grade":"4"},"otherMarks":[{"score":7,"grade":"4","count":3}]}""")
		})
		val result = gateway.gradeComparison(ReadContext("token", mapOf("schoolId" to "school", "userId" to "ignored")), "lesson", "sor")
		assertEquals("https://api.bilimclass.kz/api/mobile/comparison/classroom/lesson?uuid=lesson&type=sor", request?.url)
		assertEquals("school", request?.headers?.get("x-school-id"))
		val data = (result as GatewayReadResult.Success).value
		assertEquals(8, data.studentMark.score)
		assertEquals(3, data.otherMarks.single().count)
	}

	@Test
	fun emptyAndNotFoundAreNotZeroGrades() {
		val empty = SchoolGateway(HttpsTransport { HttpResponseData(200, "application/json", """{"maxScore":null,"studentMark":{"score":null,"grade":null},"otherMarks":[]}""") })
		val missing = SchoolGateway(HttpsTransport { HttpResponseData(404, "application/json", "{}") })
		assertEquals(GatewayReadResult.Empty, empty.gradeComparison(ReadContext("token"), "lesson", "formattedScore"))
		assertEquals(GatewayReadResult.Empty, missing.gradeComparison(ReadContext("token"), "lesson", "soch"))
	}

	@Test
	fun unsupportedTypeAndMalformedPayloadFailClosed() {
		var called = false
		val gateway = SchoolGateway(HttpsTransport { called = true; HttpResponseData(200, "application/json", "{}") })
		assertTrue(gateway.gradeComparison(ReadContext("token"), "lesson", "intermediate") is GatewayReadResult.Failure)
		assertEquals(false, called)
		assertTrue(gateway.gradeComparison(ReadContext("token"), "lesson", "sor") is GatewayReadResult.Failure)
	}

	@Test
	fun gatewayProvidesComparisonForSelectedLessonAndCategory() {
		val method = SchoolGateway::class.java.methods.firstOrNull {
			it.name == "gradeComparison" && it.parameterTypes.contentEquals(arrayOf(ReadContext::class.java, String::class.java, String::class.java))
		}
		assertNotNull("Grade comparison must be available through the gateway", method)
	}
}
