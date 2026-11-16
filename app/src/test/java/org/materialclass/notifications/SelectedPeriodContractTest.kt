package org.materialclass.notifications

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.LocalDate
import org.materialclass.api.GatewayReadResult
import org.materialclass.data.DateRange
import org.materialclass.data.DiaryPeriod
import org.materialclass.data.PeriodsModel
import org.junit.Test

class SelectedPeriodContractTest {
	@Test
	fun currentQuarterUsesServerDatesIncludingBoundariesAndBreaks() {
		val result = GatewayReadResult.Success(PeriodsModel(4, listOf(
			DiaryPeriod(1, "01.09.2026", "25.10.2026"),
			DiaryPeriod(2, "04.11.2026", "30.12.2026"),
			DiaryPeriod(3, "10.01.2027", "20.03.2027"),
			DiaryPeriod(4, "01.04.2027", "25.05.2027"),
		)))
		assertEquals("1", SelectedPeriodRange.current(result, LocalDate.of(2026, 8, 31)))
		assertEquals("1", SelectedPeriodRange.current(result, LocalDate.of(2026, 10, 25)))
		assertEquals("1", SelectedPeriodRange.current(result, LocalDate.of(2026, 11, 1)))
		assertEquals("2", SelectedPeriodRange.current(result, LocalDate.of(2026, 11, 4)))
		assertEquals("4", SelectedPeriodRange.current(result, LocalDate.of(2027, 6, 1)))
		assertNull(SelectedPeriodRange.current(GatewayReadResult.Empty, LocalDate.of(2026, 11, 4)))
	}

	@Test
	fun selectsEachServerBoundaryAndRejectsMissingInvalidOrAmbiguousDates() {
		val items = (1..4).map { DiaryPeriod(it, "0$it.01.2026", "${it + 10}.03.2026") }
		val result = GatewayReadResult.Success(PeriodsModel(4, items))
		for (i in 1..4) {
			assertEquals(DateRange(LocalDate.of(2026, 1, i), LocalDate.of(2026, 3, i + 10)), SelectedPeriodRange.resolve(i.toString(), result))
		}
		assertNull(SelectedPeriodRange.resolve("5", result))
		assertNull(SelectedPeriodRange.resolve("1", GatewayReadResult.Empty))
		assertNull(SelectedPeriodRange.resolve("1", GatewayReadResult.Success(PeriodsModel(1, listOf(DiaryPeriod(1, "31.02.2026", "01.03.2026"))))))
		assertNull(SelectedPeriodRange.resolve("1", GatewayReadResult.Success(PeriodsModel(1, listOf(DiaryPeriod(1, "02.03.2026", "01.03.2026"))))))
		assertNull(SelectedPeriodRange.resolve("1", GatewayReadResult.Success(PeriodsModel(2, listOf(items[0], items[0])))))
	}
	@Test
	fun selectedPeriodRangeResolverIsAvailable() {
		val resolver = runCatching { Class.forName("org.materialclass.notifications.SelectedPeriodRange") }.getOrNull()
		assertNotNull("Notifications must resolve the selected server period", resolver)
	}
}
