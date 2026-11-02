package org.materialclass.session

import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionCoordinatorTest {
	@Test
	fun exposesSessionCoordinatorForAccountIsolation() {
		val coordinator = try {
			Class.forName("org.materialclass.session.SessionCoordinator")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertNotNull("Account isolation requires a session coordinator", coordinator)
	}
}
