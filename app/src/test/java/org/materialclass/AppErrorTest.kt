package org.materialclass

import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
	@Test
	fun exposesOfflineError() {
		val names = try {
			Class.forName("org.materialclass.api.AppError").enumConstants
				?.map { (it as Enum<*>).name } ?: emptyList()
		} catch (_: ClassNotFoundException) {
			emptyList()
		}
		assertTrue("AppError must expose OFFLINE", "OFFLINE" in names)
	}
}
