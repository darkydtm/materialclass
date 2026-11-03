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
	fun failedReplacementRevokesGloballyAndCanRetryCleanup() {
		var fail = false
		val first = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		val second = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		val old = first.activate(AccountId("synthetic-old"))
		val initial = second.revocationEpoch()
		val delivered = CountDownLatch(1)
		var callbackHeldStorage = true
		val observer: (Long) -> Unit = { epoch ->
			if (epoch > initial) {
				callbackHeldStorage = Thread.holdsLock(org.materialclass.security.StorageLock)
				delivered.countDown()
			}
		}
		second.observeRevocations(observer)
		try {
			fail = true
			try { first.activate(AccountId("synthetic-new")); throw AssertionError("Expected cleanup failure") } catch (_: IllegalStateException) { }
			assertEquals(true, delivered.await(10, TimeUnit.SECONDS))
			assertEquals(false, callbackHeldStorage)
			assertEquals(SessionChange.LOGOUT, second.event().change)
			assertEquals(old, second.event().session)
			val ticket = second.pendingLogoutTicket()
			assertNotNull("Replacement cleanup must be retryable", ticket)
			assertNull(second.ifActive(old) { "stale" })
			assertNull(second.admit(second.beginLogin()))
			assertNull(second.beginRestore())
			fail = false
			second.logout(ticket)
			assertNull(second.pendingLogoutTicket())
			val current = second.activate(AccountId("synthetic-new"))
			assertEquals("current", first.ifActive(current) { "current" })
		} finally {
			fail = false
			second.removeRevocationObserver(observer)
			second.pendingLogoutTicket()?.let { second.logout(it) }
			second.logout()
		}
	}
	@Test
	fun delayedExpiryCannotDescribeNewlyActivatedSession() {
		val coordinator = SessionCoordinator { }
		val old = coordinator.activate(AccountId("synthetic-expired"))
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val delivered = CountDownLatch(1)
		var delayedEpoch = -1L
		val observer: (Long) -> Unit = { epoch ->
			if (coordinator.event().change == SessionChange.EXPIRED) {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				delayedEpoch = epoch
				delivered.countDown()
			}
		}
		coordinator.observeRevocations(observer)
		try {
			assertEquals(true, coordinator.revokeIfActive(old, expired = true))
			assertEquals(true, entered.await(10, TimeUnit.SECONDS))
			val current = coordinator.activate(AccountId("synthetic-new"))
			release.countDown()
			assertEquals(true, delivered.await(10, TimeUnit.SECONDS))
			assertEquals(true, delayedEpoch < coordinator.revocationEpoch())
			assertEquals(SessionChange.ACTIVATED, coordinator.event().change)
			assertEquals(current, coordinator.event().session)
			assertEquals("current", coordinator.ifActive(current) { "current" })
		} finally {
			release.countDown()
			coordinator.removeRevocationObserver(observer)
			coordinator.logout()
		}
	}

	@Test
	fun activatingAnotherAccountAdvancesGlobalEpoch() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		val old = first.activate(AccountId("synthetic-first"))
		val initial = second.revocationEpoch()
		try {
			val current = second.activate(AccountId("synthetic-second"))
			assertEquals(initial + 1, first.revocationEpoch())
			assertNull(first.ifActive(old) { "stale" })
			assertEquals("current", first.ifActive(current) { "current" })
		} finally { first.logout() }
	}

	@Test
	fun revocationsPersistAcrossCoordinatorsAndObserversCanUnsubscribe() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		val events = mutableListOf<Long>()
		val observer: (Long) -> Unit = { events.add(it) }
		val initial = second.observeRevocations(observer).first
		val token = first.cancelInFlightNow()
		assertEquals(listOf(initial + 1), events)
		assertEquals(initial + 1, second.revocationEpoch())
		second.removeRevocationObserver(observer)
		first.logout(token)
		val next = first.cancelInFlightNow()
		assertEquals(listOf(initial + 1), events)
		assertEquals(initial + 2, second.revocationEpoch())
		first.logout(next)
	}

	@Test
	fun failedTicketCanBeRecoveredAcrossCoordinatorsWithoutReleasingNewerTicket() {
		var fail = false
		val first = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		first.activate(AccountId("synthetic-retry-ticket"))
		val ticket = first.cancelInFlightNow()
		fail = true
		try { first.logout(ticket); throw AssertionError("Expected cleanup failure") } catch (_: IllegalStateException) { }
		val second = SessionCoordinator { }
		try {
			val method = SessionCoordinator::class.java.methods.firstOrNull { it.name == "pendingLogoutTicket" && it.parameterCount == 0 }
			assertNotNull("Pending logout must be recoverable after recreation", method)
			assertEquals(ticket, method!!.invoke(second))
			val newer = second.cancelInFlightNow()
			second.logout(ticket)
			assertEquals(newer, method.invoke(second))
			assertNull(second.admit(second.beginLogin()))
			second.logout(newer)
			assertNull(method.invoke(second))
		} finally {
			fail = false
			first.logout(ticket)
			second.logout()
		}
	}
	@Test
	fun staleTicketCannotRevokeNewSession() {
		val coordinator = SessionCoordinator { }
		val token = coordinator.cancelInFlightNow()
		coordinator.logout(token)
		val active = coordinator.activate(AccountId("synthetic-after-retry"))
		try {
			coordinator.logout(token)
			assertEquals("active", coordinator.ifActive(active) { "active" })
		} finally { coordinator.logout() }
	}

	@Test
	fun immediateCancellationRejectsNestedPublication() {
		val coordinator = SessionCoordinator { }
		val session = coordinator.activate(AccountId("synthetic-publication"))
		try {
			coordinator.ifActive(session) {
				coordinator.cancelInFlightNow()
				assertNull(coordinator.ifActive(session) { "stale" })
			}
		} finally {
			coordinator.logout()
		}
	}

	@Test
	fun cancellationBeforeActivationRejectsLogin() {
		val coordinator = SessionCoordinator { }
		val revision = coordinator.beginLogin()
		try {
			val session = coordinator.activateIfUnchanged(revision, AccountId("synthetic-activation")) {
				coordinator.cancelInFlightNow()
			}
			assertNull(session)
		} finally {
			coordinator.logout()
		}
	}

	@Test
	fun overlappingImmediateLogoutsKeepAdmissionClosedUntilBothFinish() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		first.activate(AccountId("synthetic-overlap"))
		first.cancelInFlightNow()
		second.cancelInFlightNow()
		first.logout()
		try {
			val revision = second.beginLogin()
			assertNull(second.admit(revision))
		} finally {
			second.logout()
		}
	}

	@Test
	fun immediateCancellationRejectsCapturedAdmissionWhileStorageIsBlocked() {
		val coordinator = SessionCoordinator { }
		val session = coordinator.activate(AccountId("synthetic-cancel"))
		val revision = coordinator.requestRevision()
		val lease = coordinator.admit(session, revision)!!
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val cancelled = CountDownLatch(1)
		val method = SessionCoordinator::class.java.methods.firstOrNull { it.name == "cancelInFlightNow" && it.parameterCount == 0 }
		assertNotNull("Immediate cancellation must be available", method)
		val writer = thread {
			coordinator.ifActive(session) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		}
		check(entered.await(10, TimeUnit.SECONDS))
		try {
			thread { method!!.invoke(coordinator); cancelled.countDown() }
			assertEquals(true, cancelled.await(2, TimeUnit.SECONDS))
			assertEquals(true, lease.isCancelled())
			assertEquals(false, lease.register { throw AssertionError("Cancelled lease registered") })
		} finally { release.countDown(); writer.join(10000) }
		assertNull(coordinator.admit(session, revision))
		coordinator.logout()
	}

	@Test
	fun retriesRestorationAfterTransientFailure() {
		val active = SessionCoordinator::class.java.getDeclaredField("active")
		active.isAccessible = true
		active.set(null, null)
		val allowed = SessionCoordinator::class.java.getDeclaredField("restorationAllowed")
		allowed.isAccessible = true
		allowed.setBoolean(null, true)
		val coordinator = SessionCoordinator { }
		val first = coordinator.beginRestore()!!
		assertNull(coordinator.finishRestore(first) { null })
		val second = coordinator.beginRestore()!!
		assertEquals(AccountId("synthetic-retry"), coordinator.finishRestore(second) { coordinator.acceptRestore(AccountId("synthetic-retry"), CacheNamespace.create()) }?.account)
		coordinator.logout()
	}
	@Test
	fun failedLogoutCannotRestoreSessionInSameProcess() {
		var fail = false
		val coordinator = SessionCoordinator { if (fail) throw IllegalStateException("synthetic failure") }
		coordinator.activate(AccountId("synthetic-account"))
		fail = true
		try { coordinator.logout() } catch (_: IllegalStateException) { }
		assertNull(coordinator.beginRestore())
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
