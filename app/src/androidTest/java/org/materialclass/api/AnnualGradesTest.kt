package org.materialclass.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.session.AccountId
import org.materialclass.session.SessionCoordinator

@RunWith(AndroidJUnit4::class)
class AnnualGradesTest {
	@Test
	fun annualReportUsesVerifiedSessionAndPreservesUnknownScores() {
		val coordinator = SessionCoordinator { }
		val active = coordinator.activate(AccountId("123"))
		val lease = coordinator.admit(active)!!
		try {
			val remote = UnverifiedSession("synthetic-token", "123", "student", SchoolContext(1, 2026, 2))
			val profile = JSONObject("""{"userId":123,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			val verified = VerifiedDiarySession.compare(remote, profile)!!
			val gateway = NativeGateway(Exchange { request ->
				assertEquals("GET", request.method)
				assertEquals("/api/v4/os/clientoffice/diary/year", request.url.path)
				assertEquals("schoolId=1&eduYear=2026", request.url.query)
				Response(200, """{"data":{"rows":[{"subjectName":"Example","attestations":{"quarter1":"0","quarter2":null},"yearScores":{"yearScore":null,"finalScore":""}}]}}""")
			})
			val method = gateway.javaClass.methods.singleOrNull { it.name == "annualGrades" }
			assertNotNull("Annual grades operation must exist", method)
			val report = (method!!.invoke(gateway, verified, lease) as GatewayResult.Success<*>).value!!
			val rows = report.javaClass.getMethod("getRows").invoke(report) as List<*>
			val row = rows.single()!!
			assertEquals("Example", row.javaClass.getMethod("getSubjectName").invoke(row))
			assertEquals(listOf("0", null, null, null), row.javaClass.getMethod("getQuarters").invoke(row))
			assertNull(row.javaClass.getMethod("getYearScore").invoke(row))
			assertEquals("", row.javaClass.getMethod("getFinalScore").invoke(row))
		} finally { coordinator.finish(lease); coordinator.logout() }
	}
}
