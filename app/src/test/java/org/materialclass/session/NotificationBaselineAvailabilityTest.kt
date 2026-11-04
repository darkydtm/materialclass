package org.materialclass.session

import org.junit.Assert.assertNotNull
import org.junit.Test

class NotificationBaselineAvailabilityTest {
	@Test
	fun exposesEncryptedNotificationBaseline() {
		val type = runCatching { Class.forName("org.materialclass.notifications.NotificationBaseline") }.getOrNull()
		assertNotNull("Notification baseline storage must be available", type)
	}
}
