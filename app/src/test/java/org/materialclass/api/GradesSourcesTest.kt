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
		queryParameters = mapOf(
			"schoolId" to "synthetic-school",
			"studentGroupUuids" to "group-a,group-b",
		),
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

		val periods = (result as GatewayReadResult.Success).value
		assertEquals(3, periods.periodCount)
		assertEquals(3, periods.periods.size)
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

		val subjects = (result as GatewayReadResult.Success).value
		assertEquals(3, subjects.subjectCount)
		assertEquals(listOf(null, "0", ""), subjects.subjects.map { it.finalScore })
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
	fun gradesUseSubjectsAndJournalSources() {
		val calls = mutableListOf<HttpRequestData>()
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				calls += request
				when {
					request.url.substringBefore('?').endsWith("/diary/subjects") -> HttpResponseData(200, "application/json", "{\"data\":[{\"subjectName\":\"Math\",\"scoreType\":\"numeric\",\"schedules\":[{\"uuid\":\"lesson-1\",\"date\":\"14.11.2026\",\"markMax\":10,\"type\":\"regular\"}]}]}")
					else -> HttpResponseData(200, "application/json", "{\"data\":{\"lesson-1\":{\"attendance\":\"was_in_class\",\"formattedScore\":{\"mark\":8,\"markMax\":10,\"comment\":null}}}}")
				}
			}),
			FakeGradesSessionController(),
		)

		val grades = gateway.grades(context)
		assertEquals("https://api.bilimclass.kz/api/v4/os/clientoffice/diary/subjects", calls.first().url.substringBefore('?'))
		assertEquals("https://journal-service.bilimclass.kz/diary/quarter", calls.last().url.substringBefore('?'))
		assertEquals("Math", (grades as GatewayReadResult.Success).value.subjects.single().subjectName)
		assertEquals(GatewayReadResult.Failure<AttendanceModel>(AppError.UnsupportedContract), gateway.attendance(context))
		assertEquals(GatewayReadResult.Failure<SubjectDetailsModel>(AppError.UnsupportedContract), gateway.subjectDetails("synthetic-subject"))
		assertEquals(3, calls.size)
	}

	@Test
	fun gradesPreserveJournalAssessmentValuesAndMaximums() {
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				if (request.url.substringBefore('?').endsWith("/diary/subjects")) {
					HttpResponseData(200, "application/json", "{\"data\":[{\"subjectName\":\"Math\",\"schedules\":[{\"uuid\":\"lesson-1\",\"date\":\"14.11.2026\"}]}]}")
				} else {
					HttpResponseData(200, "application/json", "{\"data\":{\"lesson-1\":{\"formattedScore\":{\"mark\":8,\"markMax\":10},\"sor\":{\"mark\":12,\"markMax\":15},\"soch\":{\"mark\":24,\"markMax\":30}}}}")
				}
			}),
			FakeGradesSessionController(),
		)
		val result = gateway.grades(context) as GatewayReadResult.Success
		val schedule = result.value.subjects.single().schedules.single()
		assertEquals("8", schedule.score)
		assertEquals(10, schedule.markMax)
		assertEquals("12", schedule.sor)
		assertEquals("15", schedule.sorMax)
		assertEquals("24", schedule.soch)
		assertEquals("30", schedule.sochMax)
	}

	@Test
	fun gradesCollapseIdenticalJournalDuplicatesButPreserveDifferentAssessmentMarks() {
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				if (request.url.substringBefore('?').endsWith("/diary/subjects")) {
					HttpResponseData(200, "application/json", """{"data":[{"subjectName":"Math","schedules":[{"uuid":"lesson-1","date":"14.11.2026"}]}]}""")
				} else {
					HttpResponseData(200, "application/json", """{"data":{"first":{"scheduleUuid":"lesson-1","sor":{"mark":6,"markMax":10},"soch":{"mark":12,"markMax":15}},"duplicate":{"scheduleUuid":"lesson-1","sor":{"mark":6,"markMax":10},"soch":{"mark":12,"markMax":15}},"different":{"scheduleUuid":"lesson-1","sor":{"mark":8,"markMax":10},"soch":{"mark":14,"markMax":15}}}}""")
				}
			}),
			FakeGradesSessionController(),
		)

		val schedules = (gateway.grades(context) as GatewayReadResult.Success).value.subjects.single().schedules
		assertEquals(listOf("6", "8"), schedules.mapNotNull { it.sor }.sorted())
		assertEquals(listOf("12", "14"), schedules.mapNotNull { it.soch }.sorted())
		assertEquals(2, schedules.size)
	}

	@Test
	fun gradesDeduplicateEachAssessmentIndependentlyAcrossMixedAndPartialRows() {
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				if (request.url.substringBefore('?').endsWith("/diary/subjects")) {
					HttpResponseData(200, "application/json", """{"data":[{"subjectName":"Math","schedules":[{"uuid":"lesson-1","date":"14.11.2026"},{"uuid":"lesson-2","date":"15.11.2026"}]}]}""")
				} else {
					HttpResponseData(200, "application/json", """{"data":{"one":{"scheduleUuid":"lesson-1","formattedScore":{"mark":8,"markMax":10},"sor":{"mark":6,"markMax":10},"soch":{"mark":12,"markMax":15}},"partial":{"scheduleUuid":"lesson-1","formattedScore":{"mark":9,"markMax":10},"sor":{"mark":6,"markMax":10}},"different":{"scheduleUuid":"lesson-1","sor":{"mark":8,"markMax":10},"soch":{"mark":12,"markMax":15}},"other-lesson":{"scheduleUuid":"lesson-2","sor":{"mark":6,"markMax":10}}}}""")
				}
			}),
			FakeGradesSessionController(),
		)

		val schedules = (gateway.grades(context) as GatewayReadResult.Success).value.subjects.single().schedules
		val lesson1 = schedules.filter { it.uuid == "lesson-1" }
		assertEquals(listOf("6", "8"), lesson1.mapNotNull { it.sor }.sorted())
		assertEquals(listOf("12"), lesson1.mapNotNull { it.soch }.distinct())
		assertEquals(listOf("8", "9"), lesson1.mapNotNull { it.score }.sorted())
		assertEquals(listOf("6"), schedules.filter { it.uuid == "lesson-2" }.mapNotNull { it.sor })
		assertEquals(4, schedules.size)
	}

	@Test
	fun gradeRequestsSendOnlyParametersForTheirRespectiveServices() {
		val calls = mutableListOf<HttpRequestData>()
		val source = ReadContext(
			"synthetic-access",
			mapOf("schoolId" to "school", "eduYear" to "2026", "groupId" to "class", "periodType" to "quarter", "period" to "2", "userId" to "student", "studentGroupUuids" to "group-a"),
			"synthetic-chat",
		)
		val gateway = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				calls += request
				if (request.url.substringBefore('?').endsWith("/diary/subjects")) {
					HttpResponseData(200, "application/json", "{\"data\":[{\"subjectName\":\"Math\",\"schedules\":[{\"uuid\":\"lesson-1\",\"date\":\"14.11.2026\"}]}]}")
				} else HttpResponseData(200, "application/json", "{\"data\":{}}")
			}),
			FakeGradesSessionController(),
		)
		assertTrue(gateway.grades(source) is GatewayReadResult.Success)
		fun keys(request: HttpRequestData) = java.net.URI(request.url).rawQuery.split('&').map { it.substringBefore('=') }.toSet()
		assertEquals(setOf("schoolId", "eduYear", "groupId", "periodType", "period"), keys(calls[0]))
		assertEquals(setOf("userId", "studentGroupUuid", "dateFrom", "dateTo"), keys(calls[1]))
		assertEquals("Bearer synthetic-chat", calls[1].headers["Authorization"])
	}

	@Test
	fun gradesPropagateJournalFailureInsteadOfReturningUnscoredSubjects() {
		val result = ConfirmedSchoolGateway(
			SchoolGateway(SyntheticGradesTransport { request ->
				if (request.url.substringBefore('?').endsWith("/diary/subjects")) {
					HttpResponseData(200, "application/json", "{\"data\":[{\"subjectName\":\"Math\",\"schedules\":[{\"uuid\":\"lesson-1\",\"date\":\"14.11.2026\"}]}]}")
				} else {
					HttpResponseData(403, "application/json", "{}")
				}
			}),
			FakeGradesSessionController(),
		).grades(context)

		assertEquals(GatewayReadResult.Failure<GradesModel>(AppError.HttpStatus(403)), result)
	}

	@Test
	fun journalRequestsEachStudentGroupUuidSeparately() {
		val calls = mutableListOf<HttpRequestData>()
		val result = SchoolGateway(SyntheticGradesTransport { request ->
			calls += request
			HttpResponseData(200, "application/json", "{\"data\":{}}")
		}).journalGrades(context)
		assertTrue(result is GatewayReadResult.Empty)
		assertEquals(listOf("group-a", "group-b"), calls.map { queryValue(it.url, "studentGroupUuid") })
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

	@Test
	fun scoreMapperSupportsObjectFinalScoresAndKeepsZero() {
		val result = ContractMapper.mapGrades(
			"{\"data\":{\"rows\":[{\"subjectName\":\"Safety\",\"scoreType\":\"credit\",\"yearScores\":{\"finalScore\":{\"mark\":\"Зачет\"}},\"attestations\":{\"quarter1\":{\"mark\":0}}}]}}",
		)
		val subject = (result as GatewayReadResult.Success).value.subjects.single()
		assertEquals("Зачет", subject.finalScore)
		assertEquals(listOf("0"), subject.scores)
	}

	@Test
	fun subjectsMapperPreservesSchedulesScoreTypeAndCreditResult() {
		val result = ContractMapper.mapSubjects(
			"{\"data\":[{\"subjectName\":\"Safety\",\"scoreType\":\"credit\",\"finalScore\":\"Зачет\",\"schedules\":[{\"uuid\":\"lesson-1\",\"date\":\"14.11.2026\",\"timeStart\":\"09:00\",\"markMax\":10,\"type\":\"regular\"}]}]}",
		)

		val subject = (result as GatewayReadResult.Success).value.subjects.single()
		assertEquals("credit", subject.scoreType)
		assertEquals("Зачет", subject.finalScore)
		assertEquals("lesson-1", subject.schedules.single().uuid)
		assertEquals(1, subject.schedules.size)
	}

	@Test
	fun journalMapperPreservesCreditAndNumericAssessmentValues() {
		val result = ContractMapper.mapJournalGrades(
			"{\"data\":{\"lesson-credit\":{\"attendance\":\"was_in_class\",\"formattedScore\":{\"mark\":\"Зачет\",\"markMax\":null,\"comment\":null},\"sor\":null,\"soch\":null,\"po\":null},\"lesson-number\":{\"attendance\":\"was_in_class\",\"formattedScore\":{\"mark\":8,\"markMax\":10,\"comment\":null},\"sor\":null,\"soch\":null,\"po\":null}}}",
		)

		val grades = (result as GatewayReadResult.Success).value.grades
		assertEquals("Зачет", grades.first { it.scheduleUuid == "lesson-credit" }.mark)
		assertEquals("8", grades.first { it.scheduleUuid == "lesson-number" }.mark)
	}

	@Test
	fun loginMappingPreservesEveryStudentGroupUuid() {
		val result = ContractMapper.mapLoginResponse(
			"{\"access_token\":\"a\",\"refresh_token\":\"r\",\"exp_time\":\"2030-01-01T00:00:00Z\",\"user_info\":{\"userId\":\"student\",\"studentInfo\":{\"studentGroupUuids\":[\"group-a\",\"group-b\"]}}}",
		)
		assertEquals(listOf("group-a", "group-b"), (result as GatewayLoginResult.Success).response.userInfo.studentGroupUuids)
	}
}

private fun queryValue(url: String, key: String): String = url.substringAfter("$key=").substringBefore('&')

private class SyntheticGradesTransport(
	private val responder: (HttpRequestData) -> HttpResponseData,
) : HttpsTransport {
	override fun execute(request: HttpRequestData): HttpResponseData = responder(request)
}

private class FakeGradesSessionController : SessionController {
	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession =
		ActiveSession(accountId, Generation(1))
	override fun replaceTokensIfCurrent(session: ActiveSession, expectedRefreshToken: String, accessToken: String, refreshToken: String, expiresAt: Instant): Boolean = false
	override fun invalidateIfCurrent(session: ActiveSession, expectedRefreshToken: String): Boolean = false
	override fun clearIfCurrent(session: ActiveSession?): Boolean = false

	override fun invalidateAndClear() = Unit
}
