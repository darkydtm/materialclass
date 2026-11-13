package org.materialclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNameTest {
	@Test
	fun extractsAndNormalizesTheSecondNamePart() {
		assertEquals("Иван", displayNameFromFullName("ИВАНОВ иВАН ПЕТРОВ"))
	}

	@Test
	fun returnsNoDisplayNameWhenTheSecondPartIsMissing() {
		assertNull(displayNameFromFullName("Иванов"))
	}
}