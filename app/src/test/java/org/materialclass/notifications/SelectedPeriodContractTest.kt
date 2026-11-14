package org.materialclass.notifications

import org.junit.Assert.assertNotNull
import org.junit.Test

class SelectedPeriodContractTest {
	@Test
	fun selectedPeriodRangeResolverIsAvailable() {
		val resolver = runCatching { Class.forName("org.materialclass.notifications.SelectedPeriodRange") }.getOrNull()
		assertNotNull("Notifications must resolve the selected server period", resolver)
	}
}
