package org.materialclass.api

import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeScheduleTest {
	@Test
	fun nativeGatewayExposesAccountBoundScheduleReader() {
		val method = NativeGateway::class.java.methods.singleOrNull { it.name == "schedule" && it.parameterTypes.size == 2 }
		assertNotNull("A schedule reader must accept an authenticated session and date", method)
	}
}
