package org.materialclass.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.materialclass.api.Diary
import org.materialclass.api.DiaryContext
import org.materialclass.api.DiaryDay
import org.materialclass.api.DiarySubject
import org.materialclass.notifications.DiaryObservation

class DiaryObservationTest {
	private val context = DiaryContext(1, 2026, 2, "04.11.2026")
	private fun diary(body: String?) = Diary("04.11.2026", listOf(DiaryDay("Wednesday", "04.11.2026", listOf(DiarySubject("Math", "lesson", "subject-1", "task", body, null, false, false)))))

	@Test
	fun sameContentHasSameFingerprintAndChangesAreDetected() {
		val first = DiaryObservation.from(context, diary("First"))!!
		assertEquals(first, DiaryObservation.from(context, diary("First")))
		assertNotEquals(first, DiaryObservation.from(context, diary("Second")))
		assertNotEquals(first, DiaryObservation.from(DiaryContext(1, 2026, 3, context.date), diary("First")))
	}

	@Test
	fun rejectsMismatchedDateAndDuplicateDay() {
		assertNull(DiaryObservation.from(DiaryContext(1, 2026, 2, "05.11.2026"), diary("First")))
		val day = diary("First").days.single()
		assertNull(DiaryObservation.from(context, Diary(context.date, listOf(day, day))))
	}
}
