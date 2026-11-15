package org.materialclass.api

import org.junit.Assert.assertNotNull
import org.junit.Test

class GradeComparisonTest {
	@Test
	fun gatewayProvidesComparisonForSelectedLessonAndCategory() {
		val method = SchoolGateway::class.java.methods.firstOrNull {
			it.name == "gradeComparison" && it.parameterTypes.contentEquals(arrayOf(ReadContext::class.java, String::class.java, String::class.java))
		}
		assertNotNull("Grade comparison must be available through the gateway", method)
	}
}
