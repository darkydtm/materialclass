package org.materialclass.notifications

import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionNotificationDataSourceTest {
	@Test
	fun productionSourceImplementsNotificationContract() {
		val source = runCatching { Class.forName("org.materialclass.notifications.ProductionNotificationDataSource") }.getOrNull()
		assertTrue(source != null && NotificationDataSource::class.java.isAssignableFrom(source))
	}
}
