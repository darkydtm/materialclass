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
	fun failedLogoutCannotRestoreSessionInSameProcess() {
		var fail = false
		val coordinator = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		coordinator.activate(AccountId("synthetic-account"))
		fail = true
		try { coordinator.logout() } catch (_: IllegalStateException) { }
		assertNull(coordinator.restore { AccountId("synthetic-account") })
	}

	@Test
	fun clearingFailureLeavesSessionInvalid() {
		var fail = false
		val coordinator = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		val old = coordinator.activate(AccountId("synthetic-failure"))
		fail = true
		try {
			coordinator.logout()
			throw AssertionError("Logout must report a clear failure")
		} catch (_: IllegalStateException) { }
		assertNull(coordinator.ifActive(old) { "stale" })
	}

	@Test
	fun logoutWaitsForOngoingWrite() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		val session = first.activate(AccountId("synthetic-serialization"))
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val done = CountDownLatch(1)
		val writer = thread {
			first.ifActive(session) {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			}
		}
		check(entered.await(10, TimeUnit.SECONDS))
		val logout = thread { second.logout(); done.countDown() }
		assertEquals(false, done.await(100, TimeUnit.MILLISECONDS))
		release.countDown()
		writer.join(10000)
		logout.join(10000)
		assertEquals(0L, done.count)
		assertNull(first.ifActive(session) { "stale" })
	}

	@Test
	fun recreationCannotReviveAnOldGeneration() {
		val first = SessionCoordinator { }
		val old = first.activate(AccountId("synthetic-same"))
		first.logout()
		val second = SessionCoordinator { }
		second.activate(AccountId("synthetic-same"))
		assertNull(second.ifActive(old) { "stale" })
	}

	@Test
	fun logoutInvalidatesSessionsAcrossInstances() {
		val first = SessionCoordinator { }
		val old = first.activate(AccountId("synthetic-shared"))
		SessionCoordinator { }.logout()
		assertNull(first.ifActive(old) { "stale" })
	}

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
		val coordinator = SessionCoordinator { }
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
