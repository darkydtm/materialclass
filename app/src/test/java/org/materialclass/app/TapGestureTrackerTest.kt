package org.materialclass.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGestureTrackerTest {
	@Test
	fun handledReleaseWithinSlopIsATap() {
		val tracker = TapGestureTracker(touchSlop = 8f)
		tracker.onDown(10f, 20f)

		assertTrue(tracker.onUp(17f, 20f, handled = true))
	}

	@Test
	fun unhandledReleaseIsNotATap() {
		val tracker = TapGestureTracker(touchSlop = 8f)
		tracker.onDown(10f, 20f)

		assertFalse(tracker.onUp(10f, 20f, handled = false))
	}

	@Test
	fun movementBeyondSlopDuringGestureIsNotATap() {
		val tracker = TapGestureTracker(touchSlop = 8f)
		tracker.onDown(10f, 20f)
		tracker.onMove(19f, 20f)

		assertFalse(tracker.onUp(10f, 20f, handled = true))
	}

	@Test
	fun canceledGestureCannotVibrateOnLaterRelease() {
		val tracker = TapGestureTracker(touchSlop = 8f)
		tracker.onDown(10f, 20f)
		tracker.onCancel()

		assertFalse(tracker.onUp(10f, 20f, handled = true))
	}
}
