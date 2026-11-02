package org.materialclass.session

import org.junit.Assert.assertNotNull
import org.junit.Test

class RepositoryAvailabilityTest {
	@Test
	fun exposesAccountScopedRepositoryBridge() {
		val repository = try {
			Class.forName("org.materialclass.data.SchoolRepository")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertNotNull("Account-scoped repository bridge is required", repository)
	}
}
