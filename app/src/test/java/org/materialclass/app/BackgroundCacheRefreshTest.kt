package org.materialclass.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.DateRange
import org.materialclass.data.Generation

class BackgroundCacheRefreshTest {
	private val active = ActiveSession(AccountId("synthetic"), Generation(1))
	private val date = LocalDate.of(2026, 11, 14)

	@Test
	fun runsBothRefreshesAndUsesCurrentWeek() {
		val calls = mutableListOf<String>()
		val refresh = BackgroundCacheRefresh({ true }, { active }, { date }, { session, range ->
			assertEquals(active, session)
			assertEquals(DateRange(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)), range)
			calls += "schedule"
			true
		}, { session -> assertEquals(active, session); calls += "grades"; true })
		assertTrue(refresh.run())
		assertEquals(listOf("schedule", "grades"), calls)
	}

	@Test
	fun retriesIfEitherRefreshFails() {
		for ((scheduleResult, gradesResult) in listOf(false to true, true to false, false to false)) {
			var calls = 0
			val refresh = BackgroundCacheRefresh({ true }, { active }, { date }, { _, _ -> calls++; scheduleResult }, { calls++; gradesResult })
			assertFalse(refresh.run())
			assertEquals(2, calls)
		}
	}

	@Test
	fun disabledOrMissingSessionDoesNotFetch() {
		for ((enabled, session) in listOf(false to active, true to null)) {
			val refresh = BackgroundCacheRefresh({ enabled }, { session }, { date }, { _, _ -> error("unexpected schedule") }, { error("unexpected grades") })
			assertTrue(refresh.run())
		}
	}

	@Test
	fun interruptedScheduleDoesNotStartGrades() {
		val refresh = BackgroundCacheRefresh({ true }, { active }, { date }, { _, _ -> Thread.currentThread().interrupt(); true }, { error("unexpected grades") })
		try {
			assertFalse(refresh.run())
		} finally {
			Thread.interrupted()
		}
	}
}
