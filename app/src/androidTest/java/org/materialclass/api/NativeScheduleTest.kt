package org.materialclass.api

import org.junit.Assert.*
import org.junit.Test

class NativeScheduleTest {
	private val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	private val dates = listOf("29.12.2025", "30.12.2025", "31.12.2025", "01.01.2026", "02.01.2026", "03.01.2026", "04.01.2026")
	private val body = """{"data":{"weeks":{"next":"02.01.2026","prev":"01.01.2026","next_time":2,"prev_time":1},"days":[""" + dates.mapIndexed { index, date -> """{"date":${index + 1},"dateFormat":"$date","isCurrent":false,"isHoliday":false,"schedule":[{"type":"regular","cabinet":{"id":1},"theme":{"label":"Synthetic"}}]}""" }.joinToString(",") + """ ]}}"""
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
	fun rejectsInvalidDuplicateAndWrongWeekDates() {
		val responses = listOf(
			body.replace("29.12.2025", "32.12.2025"),
			body.replace("29.12.2025", "30.12.2025"),
			body.replace("29.12.2025", "29.11.2025"),
			body.replace("29.12.2025", "TEMP").replace("30.12.2025", "29.12.2025").replace("TEMP", "30.12.2025"),
			body.replace("01.01.2026", "08.01.2026")
		)
		responses.forEach { response ->
			val gateway = NativeGateway(Exchange { Response(200, response) })
			assertEquals(AppError.INCOMPATIBLE_RESPONSE, (gateway.schedule(session(), "01.01.2026") as GatewayResult.Failure).error)
		}
		var calls = 0
		val gateway = NativeGateway(Exchange { calls++; Response(200, body) })
		assertEquals(AppError.INCOMPATIBLE_RESPONSE, (gateway.schedule(session(), "31.02.2026") as GatewayResult.Failure).error)
		assertEquals(0, calls)
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
