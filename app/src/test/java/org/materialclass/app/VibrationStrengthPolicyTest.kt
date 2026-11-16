package org.materialclass.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VibrationStrengthPolicyTest {
	@Test
	fun adjustmentRequiresApi26AndHardwareAmplitudeControl() {
		assertEquals(false, VibrationStrengthPolicy.isAdjustable(25, true, true))
		assertEquals(false, VibrationStrengthPolicy.isAdjustable(26, false, true))
		assertEquals(false, VibrationStrengthPolicy.isAdjustable(26, true, false))
		assertEquals(false, VibrationStrengthPolicy.isAdjustable(31, false, true))
		assertEquals(true, VibrationStrengthPolicy.isAdjustable(26, true, true))
	}

	@Test
	fun unsupportedHardwareUsesSystemDefaultInsteadOfPretendingToVaryStrength() {
		assertEquals(null, VibrationStrengthPolicy.amplitude(25, false, 128))
		assertEquals(-1, VibrationStrengthPolicy.amplitude(26, false, 128))
		assertEquals(128, VibrationStrengthPolicy.amplitude(26, true, 128))
		assertEquals(1, VibrationStrengthPolicy.amplitude(26, true, 0))
		assertEquals(255, VibrationStrengthPolicy.amplitude(26, true, 300))
	}
}
