package org.materialclass.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGatewayTest {
	@Test
	fun nativeGatewayIsAvailable() {
		val gateway = try {
			Class.forName("org.materialclass.api.NativeGateway")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertNotNull("Native HTTPS gateway must be available", gateway)
	}
}
