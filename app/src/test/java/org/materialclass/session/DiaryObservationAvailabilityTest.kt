package org.materialclass.session

import org.junit.Assert.assertNotNull
import org.junit.Test

class DiaryObservationAvailabilityTest {
	@Test
	fun exposesValidatedDiaryObservation() {
		val observation = try {
			Class.forName("org.materialclass.notifications.DiaryObservation")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertNotNull("Validated diary observation is required", observation)
	}
}
