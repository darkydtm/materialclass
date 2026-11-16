package org.materialclass.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugScreenLogcatTest {
	@Test
	fun exportsOnlyTheRequestedApplicationPidFromMixedOutput() {
		var command: Array<String>? = null
		val output = """
			11-16 10:00:00.000 321 321 I AppTag: app log
			11-16 10:00:00.001 654 654 E OtherTag: private other-app log
			11-16 10:00:00.002 321 321 D AppTag: another app log
		""".trimIndent()

		val result = readApplicationLogs(321) { args ->
			command = args
			FakeProcess(output, 0)
		}

		assertEquals(listOf("logcat", "-d", "-v", "threadtime"), command?.toList())
		assertEquals(
			listOf(
				"11-16 10:00:00.000 321 321 I AppTag: app log",
				"11-16 10:00:00.002 321 321 D AppTag: another app log",
			),
			result.lines(),
		)
		assertFalse(result.contains("private other-app log"))
	}

	@Test
	fun rejectsInvalidPid() {
		assertThrows(IllegalArgumentException::class.java) {
			readApplicationLogs(0) { error("must not launch logcat") }
		}
	}

	@Test
	fun excludesMalformedLinesAndLinesWithPidOnlyInMessage() {
		val output = """
			11-16 10:00:00.000 321 321 I AppTag: app line
			malformed 321 message
			11-16 10:00:00.002 654 654 E OtherTag: pid 321 in message
			11-16 10:00:00.003 321x 321 I AppTag: invalid pid
		""".trimIndent()

		val result = readApplicationLogs(321) { FakeProcess(output, 0) }

		assertEquals("11-16 10:00:00.000 321 321 I AppTag: app line", result)
	}

	@Test
	fun keepsAppLogsWhenOtherProcessUsesSamePidAsThreadId() {
		val output = """
			11-16 10:00:00.000 321 654 I OtherTag: other process
			11-16 10:00:00.001 321 321 I AppTag: app process
		""".trimIndent()

		val result = readApplicationLogs(321) { FakeProcess(output, 0) }

		assertEquals("11-16 10:00:00.001 321 321 I AppTag: app process", result)
	}

	@Test
	fun doesNotReturnOutputWhenLogcatFails() {
		assertThrows(IllegalStateException::class.java) {
			readApplicationLogs(321) { FakeProcess("11-16 10:00:00.000 321 321 I AppTag: partial", 1) }
		}
	}

	@Test
	fun reportsExecutionFailure() {
		assertThrows(IllegalStateException::class.java) {
			readApplicationLogs(321) { throw IllegalStateException("execution failed") }
		}
	}

	private class FakeProcess(output: String, private val exitCode: Int) : Process() {
		private val input = ByteArrayInputStream(output.toByteArray())

		override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
		override fun getInputStream(): InputStream = input
		override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
		override fun waitFor(): Int = exitCode
		override fun exitValue(): Int = exitCode
		override fun destroy() = Unit
	}
}
