package org.materialclass.app

import java.time.LocalDate
import org.materialclass.data.ActiveSession
import org.materialclass.data.DateRange

class BackgroundCacheRefresh(
	private val enabled: () -> Boolean,
	private val session: () -> ActiveSession?,
	private val today: () -> LocalDate,
	private val schedule: (ActiveSession, DateRange) -> Boolean,
	private val grades: (ActiveSession) -> Boolean,
) {
	fun run(): Boolean {
		if (!enabled()) return true
		val current = session() ?: return true
		val date = today()
		val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
		val scheduleResult = schedule(current, DateRange(monday, monday.plusDays(6)))
		if (Thread.currentThread().isInterrupted) return false
		val gradesResult = grades(current)
		return scheduleResult && gradesResult
	}
}
