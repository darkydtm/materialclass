package org.materialclass.notifications

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.materialclass.api.GatewayReadResult
import org.materialclass.data.DateRange
import org.materialclass.data.PeriodsModel

object SelectedPeriodRange {
	private val formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

	fun current(result: GatewayReadResult<PeriodsModel>, today: LocalDate): String? {
		val ranges = (1..4).mapNotNull { number -> resolve(number.toString(), result)?.let { number to it } }
		if (ranges.isEmpty()) return null
		return (ranges.filter { !today.isBefore(it.second.start) }.maxByOrNull { it.second.start }
			?: ranges.minByOrNull { it.second.start })?.first?.toString()
	}

	fun resolve(selected: String, result: GatewayReadResult<PeriodsModel>): DateRange? {
		val number = selected.toIntOrNull()?.takeIf { it in 1..4 } ?: return null
		val periods = (result as? GatewayReadResult.Success)?.value?.periods ?: return null
		val period = periods.filter { it.period == number }.singleOrNull() ?: return null
		return runCatching {
			val start = LocalDate.parse(period.start ?: return null, formatter)
			val end = LocalDate.parse(period.end ?: return null, formatter)
			if (end.isBefore(start)) null else DateRange(start, end)
		}.getOrNull()
	}
}
