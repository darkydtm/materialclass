package org.materialclass.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.api.AppError
import org.materialclass.api.GatewayReadResult
import org.materialclass.api.HttpResponseData
import org.materialclass.api.ReadContext
import org.materialclass.api.SchoolGateway

class DiaryDiagnosticsTest {
	@Test
	fun exceptionMessagesAndDynamicClassNamesNeverEnterLogs() {
		val secret = "private-token-https://example.test/?account=private"
		val exception = object : RuntimeException(secret) {}
		val message = diaryMessage(DiaryStage.SERIALIZE, DiaryOutcome.FAILURE, 999, AppError.Network, exception)
		assertEquals("stage=SERIALIZE outcome=FAILURE status=0 error=Network exception=OtherException", message)
		assertFalse(message.contains(secret))
		assertFalse(message.contains(exception.javaClass.name))
	}

	@Test
	fun subjectsStatusAndFailureAreLoggedWithoutRequestOrResponseData() {
		val messages = mutableListOf<String>()
		val previous = DiaryDiagnostics.sink
		try {
			DiaryDiagnostics.sink = { messages.add(it) }
			val gateway = SchoolGateway({ HttpResponseData(403, "application/json", "private-body") })
			val result = gateway.subjects(ReadContext("private-token", mapOf("schoolId" to "private-account")))
			assertEquals(GatewayReadResult.Failure<Any>(AppError.Forbidden), result)
			assertTrue(messages.any { it.contains("stage=SUBJECTS") && it.contains("status=403") })
			assertTrue(messages.any { it.contains("error=Forbidden") })
			assertFalse(messages.joinToString().contains("private"))
		} finally {
			DiaryDiagnostics.sink = previous
		}
	}

	@Test
	fun journalTransportExceptionIsClassifiedWithoutItsMessage() {
		val messages = mutableListOf<String>()
		val previous = DiaryDiagnostics.sink
		try {
			DiaryDiagnostics.sink = { messages.add(it) }
			val gateway = SchoolGateway({ throw java.net.UnknownHostException("private-host") })
			val result = gateway.journalGrades(ReadContext("private-token", mapOf("studentGroupUuid" to "private-group")))
			assertEquals(GatewayReadResult.Failure<Any>(AppError.Network), result)
			assertTrue(messages.any { it.contains("stage=JOURNAL") && it.contains("error=Network") && it.contains("exception=UnknownHostException") })
			assertFalse(messages.joinToString().contains("private"))
		} finally {
			DiaryDiagnostics.sink = previous
		}
	}
}
