package org.materialclass.session

import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRuntimeContractTest {
	@Test
	fun applicationRuntimeExposesBackgroundScheduling() {
		val runtime = Class.forName("org.materialclass.app.ApplicationRuntime")
		assertTrue("Background settings must update scheduling", runtime.methods.any { it.name == "updateBackgroundSchedule" && it.parameterCount == 0 })
	}

	@Test
	fun applicationRuntimeExposesNotificationExecution() {
		val runtime = Class.forName("org.materialclass.app.ApplicationRuntime")
		assertTrue("Worker must execute notifications through the shared runtime", runtime.methods.any { it.name == "runBackgroundNotifications" && it.parameterCount == 0 })
	}

	@Test
	fun applicationOwnsTheSharedRuntimeForActivityAndWorker() {
		val application = runCatching { Class.forName("org.materialclass.app.MaterialClassApplication") }.getOrNull()
		assertTrue("Application-scoped runtime owner is missing", application != null)
		assertTrue("Application must expose its shared runtime", application?.methods?.any { it.name == "getRuntime" && it.parameterCount == 0 } == true)
	}
}
