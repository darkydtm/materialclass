package org.materialclass.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*

@RunWith(AndroidJUnit4::class)
class ProductionWiringTest {
	@Test
	fun nativeLoginRetainsObservedStudentSchoolUuid() {
		val gateway = NativeGateway(Exchange { Response(200, """{"success":true,"access_token":"synthetic-token","user_info":{"role":"student","userId":42,"school_id":7,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"12345678-1234-1234-1234-123456789abc"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}}""") })
		val result = gateway.login("synthetic", charArrayOf('x')) as GatewayResult.Success
		assertEquals("12345678-1234-1234-1234-123456789abc", result.value.context?.studentSchoolUuid)
	}
}
