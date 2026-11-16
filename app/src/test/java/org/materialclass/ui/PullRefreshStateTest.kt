package org.materialclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshStateTest {
	@Test
	fun releaseBelowThresholdCancelsWithoutRefreshing() {
		val state = PullRefreshState(threshold = 100f)
		state.dragBy(50f)

		assertFalse(state.release())
		assertEquals(0f, state.progress)
		assertFalse(state.isRefreshing)
	}

	@Test
	fun draggingBackBelowThresholdCancels() {
		val state = PullRefreshState(threshold = 100f)
		state.dragBy(100f)
		state.dragBy(-20f)

		assertFalse(state.release())
		assertEquals(80f, state.progress)
	}

	@Test
	fun crossingThresholdStartsOneRefreshAndBlocksFurtherPulls() {
		val state = PullRefreshState(threshold = 100f)
		state.dragBy(600f)

		assertTrue(state.release())
		assertTrue(state.isRefreshing)
		state.dragBy(600f)
		assertFalse(state.release())
		assertTrue(state.isRefreshing)
	}

	@Test
	fun completionResetsPullAndAllowsAnotherRefresh() {
		val state = PullRefreshState(threshold = 100f)
		state.dragBy(600f)
		state.release()

		state.finish()
		assertFalse(state.isRefreshing)
		assertEquals(0f, state.progress)
	}
}
