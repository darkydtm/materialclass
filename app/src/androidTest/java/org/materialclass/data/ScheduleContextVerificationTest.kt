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

	@Test
	fun rejectsMismatchesAndAmbiguousProfileWhileKeepingIdentity() {
		val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
		val session = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2, uuid))
		val profile = """{"data":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"$uuid"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}"""
		val variants = listOf(
			profile.replace("\"school_id\":7", "\"school_id\":8"),
			profile.replace("\"id\":2", "\"id\":3"),
			profile.replace("\"eduYear\":2026", "\"eduYear\":2025"),
			profile.replace(uuid, "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"),
			profile.replace(uuid, "not-a-uuid"),
			profile.replace("{\"isCurrent\":true,\"eduYear\":2026}", "{\"isCurrent\":true,\"eduYear\":2026},{\"isCurrent\":true,\"eduYear\":2025}"),
			profile.replace("{\"isCurrent\":true,\"eduYear\":2026}", "{\"isCurrent\":true,\"eduYear\":2026},{}"),
			"""{"data":{"role":"student","userId":42}}"""
		)
		variants.forEach { response ->
			val result = NativeIdentityVerifier(Exchange { Response(200, response) }).verify(session) as SessionVerification.Verified
			assertNull(result.scheduleSession)
		}
		val changedUuid = profile.replace(uuid, "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")
		val mismatch = NativeIdentityVerifier(Exchange { Response(200, changedUuid) }).verify(session) as SessionVerification.Verified
		assertNull(mismatch.diarySession)
		assertNull(mismatch.profile)
		listOf("null", "42", "\"not-a-uuid\"").forEach { value ->
			val ambiguous = profile.replace("\"$uuid\"", value)
			val result = NativeIdentityVerifier(Exchange { Response(200, ambiguous) }).verify(session) as SessionVerification.Verified
			assertNull(result.diarySession)
			assertNull(result.scheduleSession)
		}
		val noUuid = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2))
		assertNotNull((NativeIdentityVerifier(Exchange { Response(200, profile) }).verify(noUuid) as SessionVerification.Verified).diarySession)
		val bad = UnverifiedSession("synthetic-token", "42", "student", SchoolContext(7, 2026, 2, "invalid"))
		assertNull((NativeIdentityVerifier(Exchange { Response(200, profile.replace(uuid, "invalid")) }).verify(bad) as SessionVerification.Verified).scheduleSession)
		assertFalse(NativeIdentityVerifier(Exchange { Response(200, profile.replace("\"userId\":42", "\"userId\":43")) }).verify(session) is SessionVerification.Verified)
		assertFalse(NativeIdentityVerifier(Exchange { Response(200, profile.replace("student", "teacher")) }).verify(session) is SessionVerification.Verified)
	}
}
