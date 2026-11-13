package org.materialclass.api

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.AttendanceModel
import org.materialclass.data.Generation
import org.materialclass.data.GradesModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.SubjectDetailsModel
import org.materialclass.data.SubjectsModel

class GradesSourcesTest {
	private val context = ReadContext(
		accessToken = "synthetic-access",
		queryParameters = mapOf("schoolId" to "synthetic-school"),
	)

	@Test
	fun periodsMapOnlyTheConfirmedArrayCardinality() {
		val transport = SyntheticGradesTransport { request ->
			assertEquals("GET", request.method)
			assertEquals("https://api.bilimclass.kz/api/v4/os/clientoffice/diary/periods", request.url.substringBefore('?'))
			HttpResponseData(
				200,
				"application/json",
				"{\"data\":{\"periods\":[{\"id\":null},{\"id\":0},{\"id\":\"\"}]}}",
			)
		}

		val result = SchoolGateway(transport).diaryPeriods(context)

		assertEquals(GatewayReadResult.Success(PeriodsModel(periodCount = 3)), result)
	}

	@Test
	fun emptyConfirmedPeriodsRemainEmpty() {
		val result = SchoolGateway(SyntheticGradesTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"periods\":[]}}")
		}).diaryPeriods(context)

		assertEquals(GatewayReadResult.Empty, result)
	}

	@Test
	fun subjectsMapOnlyTheConfirmedArrayCardinalityWithoutInterpretingFinalScore() {
		val result = SchoolGateway(SyntheticGradesTransport {
			HttpResponseData(
				200,
				"application/json",
				"{\"data\":[{\"subjectId\":null,\"finalScore\":null},{\"subjectId\":0,\"finalScore\":0},{\"subjectId\":\"\",\"finalScore\":\"\"}]}",
			)
		}).subjects(context)

		assertEquals(GatewayReadResult.Success(SubjectsModel(subjectCount = 3)), result)
	}

	@Test
	fun malformedPeriodsAndSubjectsAreRejectedAsInvalidResponses() {
		val periods = SchoolGateway(SyntheticGradesTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"periods\":[null]}}")
		}).diaryPeriods(context)
		val subjects = SchoolGateway(SyntheticGradesTransport {
			HttpResponseData(200, "application/json", "{\"data\":{\"subjects\":[]}}")
		}).subjects(context)

		assertEquals(GatewayReadResult.Failure<PeriodsModel>(AppError.InvalidResponse), periods)
		assertEquals(GatewayReadResult.Failure<SubjectsModel>(AppError.InvalidResponse), subjects)
	}

	@Test
	fun subjectsPropagateConfirmedReadErrorsWithoutInventingAnEmptyResult() {
		val result = SchoolGateway(SyntheticGradesTransport {
			HttpResponseData(403, "application/json", "{}")
		}).subjects(context)

		assertEquals(GatewayReadResult.Failure<SubjectsModel>(AppError.Forbidden), result)
	}

	@Test
	fun yearGradesUseTheConfirmedYearEndpointWhileOtherSourcesStayUnsupported() {
		val calls = mutableListOf<HttpRequestData>()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				calls += request
				HttpResponseData(200, "application/json", "{\"data\":{\"rows\":[{\"subjectName\":\"Math\",\"attestations\":{\"quarter1\":\"5\"},\"yearScores\":{\"yearScore\":\"5\",\"finalScore\":null},\"attendances\":{\"totalMissCount\":0}}]}}")
			}),
			FakeGradesSessionController(),
		)

		val grades = gateway.grades(context)
		assertEquals("https://api.bilimclass.kz/api/v4/os/clientoffice/diary/year", calls.single().url.substringBefore('?'))
		assertEquals("Math", (grades as GatewayReadResult.Success).value.subjects.single().subjectName)
		assertEquals(GatewayReadResult.Failure<AttendanceModel>(AppError.UnsupportedContract), gateway.attendance(context))
		assertEquals(GatewayReadResult.Failure<SubjectDetailsModel>(AppError.UnsupportedContract), gateway.subjectDetails("synthetic-subject"))
		assertEquals(1, calls.size)
	}

	@Test
	fun yearGradeMapperKeepsNullScoresDistinctFromEmptyValues() {
		val result = ContractMapper.mapGrades(
			"{\"data\":{\"rows\":[{\"subjectName\":\"Math\",\"attestations\":{\"quarter1\":null,\"quarter2\":\"\"},\"yearScores\":null,\"attendances\":{\"totalMissCount\":0}}]}}",
		)

		val subject = (result as GatewayReadResult.Success).value.subjects.single()
		assertEquals(null, subject.quarter1)
		assertEquals("", subject.quarter2)
		assertEquals(null, subject.finalScore)
		assertEquals(0, subject.totalMissCount)
	}

	@Test
	fun yearGradeMapperExtractsScoresFromStructuredAttestations() {
		val result = ContractMapper.mapGrades(
			"{\"data\":{\"rows\":[{\"subjectName\":\"Math\",\"attestations\":{\"a\":{\"mark\":\"8\"},\"b\":{\"value\":\"9\"},\"c\":null}}]}}",
		)
		assertEquals(listOf("8", "9"), (result as GatewayReadResult.Success).value.subjects.single().scores)
	}
}

private class SyntheticGradesTransport(
	private val responder: (HttpRequestData) -> HttpResponseData,
) : HttpsTransport {
	override fun execute(request: HttpRequestData): HttpResponseData = responder(request)
}

private class FakeGradesSessionController : SessionController {
	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession =
		ActiveSession(accountId, Generation(1))

	override fun invalidateAndClear() = Unit
}
