package org.materialclass.data

import org.junit.Assert.*
import org.junit.Test
import org.materialclass.api.*
import org.materialclass.session.SessionVerification

class ScheduleContextVerificationTest {
	@Test
	fun profileVerificationExposesScheduleProofOnlyAfterComparison() {
		val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
		val session = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2, uuid))
		val response = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"$uuid"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		val result = NativeIdentityVerifier(Exchange { Response(200, response) }).verify(session) as SessionVerification.Verified
		val accessor = result.javaClass.methods.singleOrNull { it.name == "getScheduleSession" }
		assertNotNull(accessor)
		assertNotNull(accessor!!.invoke(result))
	}
}
