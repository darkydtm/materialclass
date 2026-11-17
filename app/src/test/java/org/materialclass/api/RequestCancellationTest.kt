package org.materialclass.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestCancellationTest {
	@Test
	fun cancellationRunsRegisteredCallbacksOnlyOnce() {
		val cancellation = RequestCancellation()
		var calls = 0
		cancellation.invokeOnCancel { calls++ }

		cancellation.cancel()
		cancellation.cancel()

		assertEquals(true, cancellation.isCancelled)
		assertEquals(1, calls)
	}

	@Test
	fun alreadyCancelledSignalRejectsLoginBeforeTransport() {
		var transportCalls = 0
		val gateway = SchoolGateway(HttpsTransport {
			transportCalls++
			HttpResponseData(401, "application/json", "{}")
		})
		val cancellation = RequestCancellation().apply { cancel() }

		assertThrows(java.util.concurrent.CancellationException::class.java) {
			gateway.login(LoginCredentials("student", "password"), cancellation)
		}
		assertEquals(0, transportCalls)
	}
}
