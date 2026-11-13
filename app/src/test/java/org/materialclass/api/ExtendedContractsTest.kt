package org.materialclass.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedContractsTest {
	@Test
	fun mapsConfirmedHomeworkJournalAndSystemShapes() {
		val homeworks = ContractMapper.mapHomeworks("{\"data\":[{\"uuid\":\"h-1\",\"subjectName\":\"Math\",\"theme\":null,\"currentLessonTheme\":\"Fractions\",\"body\":\"Read\",\"date\":\"2026-11-13\",\"hasFiles\":true}]}")
		val homework = (homeworks as GatewayReadResult.Success).value.items.single()
		assertEquals("Fractions", homework.theme)
		assertEquals(true, homework.hasFiles)

		val gradeResult = ContractMapper.mapJournalGrades("{\"data\":{\"schedule-1\":{\"attendance\":\"was_in_class\",\"formattedScore\":{\"mark\":8,\"markMax\":10,\"comment\":null},\"sor\":null,\"soch\":null,\"po\":null}}}")
		val grade = (gradeResult as GatewayReadResult.Success).value.grades.single()
		assertEquals("schedule-1", grade.scheduleUuid)
		assertEquals("8", grade.mark)
		assertEquals("10", grade.markMax)

		val system = ContractMapper.mapSystem("{\"data\":{\"currentSessionId\":\"session\",\"rows\":[{}]}}")
		val systemValue = (system as GatewayReadResult.Success).value
		assertTrue(systemValue.dataKeys.contains("rows"))
		assertEquals(1, systemValue.itemCount)
	}

	@Test
	fun emptyJournalDataIsNotAnInvalidResponse() {
		assertEquals(GatewayReadResult.Empty, ContractMapper.mapJournalGrades("{\"data\":[]}"))
	}

	@Test
	fun refreshAndBinaryRoutesPreserveTheirContracts() {
		val responses = ArrayDeque<HttpResponseData>()
		responses += HttpResponseData(200, "application/json", "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"exp_time\":\"2030-01-01T00:00:00Z\"}")
		responses += HttpResponseData(200, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "", byteArrayOf(0x50, 0x4b, 0x03, 0x04))
		val requests = mutableListOf<HttpRequestData>()
		val gateway = SchoolGateway(HttpsTransport { request -> requests += request; responses.removeFirst() })

		assertTrue(gateway.refresh("refresh-value") is GatewayRefreshResult.Success)
		assertEquals("POST", requests[0].method)
		assertTrue(requests[0].body.contains("refreshToken"))
		val excel = gateway.downloadSubjectsExcel(ReadContext("access")) as HttpResult.Success
		assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), excel.body)
		assertTrue(requests[1].headers["Accept"].orEmpty().contains("spreadsheet"))
	}
}