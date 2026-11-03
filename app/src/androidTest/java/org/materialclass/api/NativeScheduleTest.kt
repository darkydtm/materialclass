package org.materialclass.api

import org.junit.Assert.*
import org.junit.Test

class NativeScheduleTest {
	private val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	private val body = """{"data":{"weeks":{"next":"02.01.2026","prev":"01.01.2026","next_time":2,"prev_time":1},"days":[""" + List(7) { """{"date":1,"dateFormat":"01.01.2026","isCurrent":true,"isHoliday":false,"schedule":[{"type":"regular","cabinet":{"id":1},"theme":{"label":"Synthetic"}}]}""" }.joinToString(",") + """ ]}}"""
	private fun session(id: String = "1", schoolUuid: String? = uuid) = VerifiedScheduleSession(UnverifiedSession("token", id, "student", SchoolContext(1, 2026, 2, schoolUuid)), id)

	@Test
	fun nativeGatewayExposesAccountBoundScheduleReader() {
		val method = NativeGateway::class.java.methods.singleOrNull { it.name == "schedule" && it.parameterTypes.size == 2 }
		assertNotNull(method)
	}

	@Test
	fun readsObservedFieldsAndBuildsScopedRequest() {
		var captured: Request? = null
		val gateway = NativeGateway(Exchange { captured = it; Response(200, body) })
		val result = gateway.schedule(session(), "01.01.2026") as GatewayResult.Success
		assertEquals("Synthetic", result.value.days.first().lessons.single().themeLabel)
		assertEquals("GET", captured!!.method)
		assertEquals("api.bilimclass.kz", captured!!.url.host)
		assertTrue(captured!!.url.query.contains("studentSchoolUuid=$uuid"))
		assertTrue(captured!!.url.query.contains("groupId=2"))
	}

	@Test
	fun rejectsMalformedAndMissingContext() {
		var calls = 0
		val gateway = NativeGateway(Exchange { calls++; Response(200, body.replace("\"id\":1", "\"id\":null")) })
		assertEquals(AppError.INCOMPATIBLE_RESPONSE, (gateway.schedule(session(), "01.01.2026") as GatewayResult.Failure).error)
		assertEquals(AppError.INCOMPATIBLE_RESPONSE, (gateway.schedule(session(schoolUuid = null), "01.01.2026") as GatewayResult.Failure).error)
		assertEquals(1, calls)
	}
}
