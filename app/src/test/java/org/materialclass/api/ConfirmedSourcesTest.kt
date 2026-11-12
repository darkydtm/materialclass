package org.materialclass.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.DiaryModel
import org.materialclass.data.ScheduleModel

class ConfirmedSourcesTest {
	private val context = ReadContext(
		accessToken = "synthetic-access",
		queryParameters = mapOf("synthetic-context" to "current"),
	)

	@Test
	fun diaryMapsConfirmedDaysAndKeepsEmptyDistinct() {
		val transport = SyntheticReadTransport { request ->
			assertEquals("GET", request.method)
			assertEquals("https://api.bilimclass.kz/api/v4/os/clientoffice/diary", request.url.substringBefore('?'))
			HttpResponseData(200, "application/json", "{\"data\":{\"date\":\"synthetic\",\"days\":[{},{}]}}")
		}

		val result = SchoolGateway(transport).diary(context)

		assertEquals(GatewayReadResult.Success(DiaryModel(dayCount = 2)), result)
		assertEquals("Bearer synthetic-access", transport.lastRequest!!.headers["Authorization"])
	}

	@Test
	fun emptyDiaryAndScheduleAreSuccessfulEmptyResults() {
		val diary = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"days\":[]}}")
		}).diary(context)
		val schedule = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"days\":[]}}")
		}).schedule(context)

		assertEquals(GatewayReadResult.Empty, diary)
		assertEquals(GatewayReadResult.Empty, schedule)
	}

	@Test
	fun weeksAndScheduleMapOnlyObservedStructuralFacts() {
		val weeks = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"weeks\":[{\"label\":\"synthetic week\",\"value\":\"synthetic value\"}],\"periods\":[]}}")
		}).diaryWeeks(context)
		val schedule = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"days\":[{\"schedule\":[]},{},{}]}}")
		}).schedule(context)

		assertEquals(1, (weeks as GatewayReadResult.Success).value.weekCount)
		assertEquals(0, weeks.value.periodCount)
		assertEquals(ScheduleModel(dayCount = 3, daysWithSchedule = 1), (schedule as GatewayReadResult.Success).value)
	}

	@Test
	fun partialOrMalformedSlicesAreRejectedBeforeTheyCanBeCached() {
		val partial = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"days\":[{},1]}}")
		}).diary(context)
		val malformed = SchoolGateway(SyntheticReadTransport {
			HttpResponseData(200, "application/json", "{\"data\":null}")
		}).schedule(context)

		assertEquals(GatewayReadResult.Failure(AppError.InvalidResponse), partial)
		assertEquals(GatewayReadResult.Failure(AppError.InvalidResponse), malformed)
	}

	@Test
	fun forbiddenServerTimeoutAndOfflineAreTyped() {
		assertEquals(GatewayReadResult.Failure(AppError.Forbidden), read(HttpResponseData(403, "application/json", "{}")))
		assertEquals(GatewayReadResult.Failure(AppError.Server), read(HttpResponseData(503, "application/json", "{}")))
		assertEquals(GatewayReadResult.Failure(AppError.Timeout), readFailure(TransportFailure.Timeout))
		assertEquals(GatewayReadResult.Failure(AppError.Offline), readFailure(TransportFailure.Offline))
	}

	@Test
	fun homeAndLessonDetailRemainExplicitlyUnsupportedWithoutTransportCalls() {
		val transportCalls = mutableListOf<HttpRequestData>()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticReadTransport { request ->
				transportCalls += request
				HttpResponseData(500, "application/json", "{}")
			}),
			FakeSessionController(),
		)

		assertEquals(GatewayReadResult.Failure(AppError.UnsupportedContract), gateway.home())
		assertEquals(GatewayReadResult.Failure(AppError.UnsupportedContract), gateway.lessonDetail("synthetic-lesson"))
		assertTrue(transportCalls.isEmpty())
	}

	@Test
	fun confirmedScheduleWeeksUseTheObservedScheduleSource() {
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticReadTransport {
				HttpResponseData(200, "application/json", "{\"data\":{\"days\":[{}]}}")
			}),
			FakeSessionController(),
		)

		assertEquals(GatewayReadResult.Success(ScheduleModel(dayCount = 1)), gateway.scheduleWeeks(context))
	}

	private fun read(response: HttpResponseData): GatewayReadResult<DiaryModel> =
		SchoolGateway(SyntheticReadTransport { response }).diary(context)

	private fun readFailure(failure: TransportFailure): GatewayReadResult<DiaryModel> =
		SchoolGateway(SyntheticReadTransport { throw failure }).diary(context)
}

private class SyntheticReadTransport(
	private val responder: (HttpRequestData) -> HttpResponseData,
) : HttpsTransport {
	var lastRequest: HttpRequestData? = null

	override fun execute(request: HttpRequestData): HttpResponseData {
		lastRequest = request
		return responder(request)
	}
}
