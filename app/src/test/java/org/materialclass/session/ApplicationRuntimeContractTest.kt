package org.materialclass.session

import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRuntimeContractTest {
	@Test
	fun applicationOwnsTheSharedRuntimeForActivityAndWorker() {
		val application = runCatching { Class.forName("org.materialclass.app.MaterialClassApplication") }.getOrNull()
		assertTrue("Application-scoped runtime owner is missing", application != null)
		assertTrue("Application must expose its shared runtime", application?.methods?.any { it.name == "getRuntime" && it.parameterCount == 0 } == true)
	}
}
