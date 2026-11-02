package org.materialclass.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SessionCoordinatorTest {
	@Test
	fun exposesSessionCoordinatorForAccountIsolation() {
		val coordinator = try {
			Class.forName("org.materialclass.session.SessionCoordinator")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertNotNull("Account isolation requires a session coordinator", coordinator)
	}

	@Test
	fun rejectsDelayedWorkAfterLogoutAndAccountSwitch() {
		val coordinator = SessionCoordinator()
		val a = coordinator.activate(AccountId("synthetic-a"))
		val ready = CountDownLatch(1)
		val release = CountDownLatch(1)
		val events = mutableListOf<String>()
		val worker = thread {
			ready.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			coordinator.ifActive(a) { events.add("write and emit") }
		}
		check(ready.await(10, TimeUnit.SECONDS))
		coordinator.logout()
		val b = coordinator.activate(AccountId("synthetic-b"))
		release.countDown()
		worker.join(10000)
		check(!worker.isAlive)
		assertEquals(emptyList<String>(), events)
		assertNull(coordinator.ifActive(a) { "data" })
		assertEquals("b", coordinator.ifActive(b) { "b" })
	}
}
