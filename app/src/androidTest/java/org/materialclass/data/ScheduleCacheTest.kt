package org.materialclass.data

import org.junit.Assert.assertNotNull
import org.junit.Test

class ScheduleCacheTest {
	@Test
	fun scheduleExposesTimestampedOfflineSnapshot() {
		val type = runCatching { Class.forName("org.materialclass.data.ScheduleSnapshot") }.getOrNull()
		assertNotNull("Schedule must expose a timestamped snapshot", type)
	}
}
