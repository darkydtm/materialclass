package org.materialclass.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugScreenLogcatTest {
	@Test
	fun exportsOnlyTheRequestedApplicationPid() {
		var command: Array<String>? = null
		val output = "app log"

		val result = readApplicationLogs(321) { args ->
			command = args
			FakeProcess(output, 0)
		}

		assertEquals(listOf("logcat", "-d", "--pid=321"), command?.toList())
		assertEquals(output, result)
	}

	@Test
	fun rejectsInvalidPid() {
		assertThrows(IllegalArgumentException::class.java) {
			readApplicationLogs(0) { error("must not launch logcat") }
		}
	}

	@Test
	fun failsWhenLogcatReturnsNonzero() {
		assertThrows(IllegalStateException::class.java) {
			readApplicationLogs(321) { FakeProcess("", 1) }
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
