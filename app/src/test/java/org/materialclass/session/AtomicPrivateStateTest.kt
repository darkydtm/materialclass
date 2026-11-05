package org.materialclass.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.materialclass.NativeUiState
import org.materialclass.security.StorageLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class AtomicPrivateState<T>(private val empty: T) {
	class Epoch
	data class Snapshot<T>(val epoch: Epoch, val payload: T)

	private val state = AtomicReference(Snapshot(Epoch(), empty))

	fun read(): Snapshot<T> = state.get()

	fun commit(epoch: Epoch, update: (T) -> T): Boolean {
		while (true) {
			val current = state.get()
			if (current.epoch !== epoch) return false
			val next = Snapshot(epoch, update(current.payload))
			if (state.compareAndSet(current, next)) return true
		}
	}

	fun revoke(): Epoch {
		val next = Snapshot(Epoch(), empty)
		state.getAndSet(next)
		return next.epoch
	}
}

class AtomicPrivateStateTest {
	@Test
	fun loginInvalidatesLeaseTokenWithoutClearingExistingState() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		val registry = SessionCoordinator.privateStates
		val id = registry.register()
		try {
			val active = first.activate(AccountId("synthetic-login"))
			val lease = second.admit(active)!!
			val token = lease.privateToken!!
			assertTrue(registry.commit(token, id) { it.copy(signedIn = true) })
			first.beginLogin()
			assertFalse(registry.commit(token, id) { NativeUiState() })
			assertTrue(registry.read().states[id]!!.signedIn)
			assertTrue(lease.isCancelled())
		} finally { registry.unregister(id); first.logout() }
	}

	@Test
	fun schoolInvalidationRejectsOldTokenAndPreservesState() {
		val first = SessionCoordinator { }
		val second = SessionCoordinator { }
		val registry = SessionCoordinator.privateStates
		val id = registry.register()
		try {
			val active = first.activate(AccountId("synthetic-school"))
			val old = second.admit(active)!!
			val selected = first.admit(active)!!
			val token = old.privateToken!!
			assertTrue(registry.commit(token, id) { it.copy(signedIn = true) })
			first.invalidateSchoolRequests(active, selected)
			assertFalse(registry.commit(token, id) { NativeUiState() })
			assertTrue(registry.read().states[id]!!.signedIn)
			assertTrue(old.isCancelled())
			assertFalse(selected.isCancelled())
		} finally { registry.unregister(id); first.logout() }
	}

	@Test
	fun admissionBindsRegistryTokenToTheLeaseBeforeAccountReplacement() {
		val coordinator = SessionCoordinator { }
		val registry = SessionCoordinator.privateStates
		val id = registry.register()
		try {
			val first = coordinator.activate(AccountId("synthetic-admission-a"))
			val lease = coordinator.admit(first)!!
			val field = RequestLease::class.java.declaredFields.firstOrNull { it.type == PrivateStateRegistry.Token::class.java }
			assertTrue("Admission must bind a registry token to the request lease", field != null)
			field!!.isAccessible = true
			val token = field.get(lease) as PrivateStateRegistry.Token
			coordinator.activate(AccountId("synthetic-admission-b"))
			assertFalse(registry.commit(token, id) { it.copy(signedIn = true) })
			assertEquals(NativeUiState(), registry.read().states[id])
		} finally {
			registry.unregister(id)
			coordinator.logout()
		}
	}

	@Test
	fun productionCommitLosingCasToRevokeIsRejected() {
		val entered = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val registry = PrivateStateRegistry { entered.countDown(); resume.awaitOrFail() }
		val id = registry.register()
		val token = registry.read().token
		val reducer: (NativeUiState) -> NativeUiState = { it.copy(signedIn = true) }
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit<Boolean> { registry.commit(token, id, reducer) }
			entered.awaitOrFail()
			registry.revokeAndClear()
			resume.countDown()
			assertFalse(result.get(5, TimeUnit.SECONDS))
			assertEquals(NativeUiState(), registry.read().states[id])
		} finally { resume.countDown(); executor.shutdownNow() }
	}

	@Test
	fun productionCommitBeforeRevokeClearsBothClientsAndRejectsAba() {
		val registry = PrivateStateRegistry()
		val first = registry.register()
		val second = registry.register()
		val initial = registry.read().token
		val reducer: (NativeUiState) -> NativeUiState = { it.copy(signedIn = true) }
		assertTrue(registry.commit(initial, first, reducer))
		assertTrue(registry.commit(initial, second, reducer))
		val previous = registry.read()
		registry.revokeAndClear()
		assertTrue(previous.states[first]!!.signedIn)
		assertEquals(NativeUiState(), registry.read().states[first])
		assertEquals(NativeUiState(), registry.read().states[second])
		registry.revokeAndClear()
		assertNotSame(initial, registry.read().token)
		assertFalse(registry.commit(initial, first, reducer))
		registry.unregister(first)
		assertFalse(registry.commit(registry.read().token, first, reducer))
		assertFalse(registry.commit(registry.read().token, second, reducer))
		registry.activateAndClear()
		assertTrue(registry.commit(registry.read().token, second, reducer))
	}

	@Test
	fun coordinatorRevokesRegistryWhileStorageAndCallbackAreBlocked() {
		val coordinator = SessionCoordinator { }
		coordinator.activate(AccountId("synthetic-registry"))
		val registry = SessionCoordinator.privateStates
		val first = registry.register()
		val second = registry.register()
		val token = registry.read().token
		val reducer: (NativeUiState) -> NativeUiState = { it.copy(signedIn = true) }
		assertTrue(registry.commit(token, first, reducer))
		assertTrue(registry.commit(token, second, reducer))
		val held = CountDownLatch(1)
		val release = CountDownLatch(1)
		val callbackEntered = CountDownLatch(1)
		val callbackRelease = CountDownLatch(1)
		val observer: (Long, () -> Unit) -> Unit = { _, acknowledge ->
			callbackEntered.countDown()
			callbackRelease.awaitOrFail()
			acknowledge()
		}
		coordinator.observeUiRevocations(observer)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val storage = executor.submit { synchronized(StorageLock) { held.countDown(); release.awaitOrFail() } }
			held.awaitOrFail()
			val cancel = executor.submit<Any> { coordinator.cancelInFlightNow() }
			callbackEntered.awaitOrFail()
			assertNotSame(token, registry.read().token)
			assertEquals(NativeUiState(), registry.read().states[first])
			assertEquals(NativeUiState(), registry.read().states[second])
			assertFalse(registry.commit(token, first, reducer))
			callbackRelease.countDown()
			val ticket = cancel.get(5, TimeUnit.SECONDS)
			release.countDown()
			storage.get(5, TimeUnit.SECONDS)
			coordinator.logout(ticket)
		} finally {
			callbackRelease.countDown(); release.countDown()
			coordinator.removeUiRevocationObserver(observer)
			registry.unregister(first); registry.unregister(second)
			executor.shutdownNow()
			coordinator.pendingLogoutTicket()?.let { coordinator.logout(it) }
		}
	}

	@Test
	fun coordinatorChangesTokenOnAccountReplacement() {
		val coordinator = SessionCoordinator { }
		val registry = SessionCoordinator.privateStates
		val id = registry.register()
		try {
			coordinator.activate(AccountId("synthetic-a"))
			val old = registry.read().token
			val reducer: (NativeUiState) -> NativeUiState = { it.copy(signedIn = true) }
			assertTrue(registry.commit(old, id, reducer))
			coordinator.activate(AccountId("synthetic-b"))
			assertNotSame(old, registry.read().token)
			assertEquals(NativeUiState(), registry.read().states[id])
			assertFalse(registry.commit(old, id, reducer))
		} finally { registry.unregister(id); coordinator.logout() }
	}
	@Test
	fun productionRegistryExists() {
		val registry = try {
			Class.forName("org.materialclass.session.PrivateStateRegistry")
		} catch (_: ClassNotFoundException) {
			null
		}
		assertTrue("Production private state registry is required", registry != null)
	}

	private fun CountDownLatch.awaitOrFail() {
		assertTrue("Timed out waiting for barrier", await(5, TimeUnit.SECONDS))
	}

	@Test
	fun revokeBeforeCommitRejectsStaleUpdateAndReadIsEmpty() {
		val state = AtomicPrivateState("")
		val epoch = state.read().epoch
		assertTrue(state.commit(epoch) { "private" })
		val entered = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit<Boolean> {
				state.commit(epoch) {
					entered.countDown()
					resume.awaitOrFail()
					"stale"
				}
			}
			entered.awaitOrFail()
			val next = state.revoke()
			assertEquals("", state.read().payload)
			resume.countDown()
			assertFalse(result.get(5, TimeUnit.SECONDS))
			assertEquals("", state.read().payload)
			assertNotSame(epoch, next)
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun commitBeforeRevokeIsCleared() {
		val state = AtomicPrivateState("")
		val epoch = state.read().epoch
		val committed = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit<Boolean> {
				val success = state.commit(epoch) { "private" }
				committed.countDown()
				resume.awaitOrFail()
				success
			}
			committed.awaitOrFail()
			assertEquals("private", state.read().payload)
			state.revoke()
			assertEquals("", state.read().payload)
			resume.countDown()
			assertTrue(result.get(5, TimeUnit.SECONDS))
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun returningToSameAccountDoesNotReviveOldEpoch() {
		val state = AtomicPrivateState("")
		val first = state.read().epoch
		assertTrue(state.commit(first) { "account A" })
		val second = state.revoke()
		assertTrue(state.commit(second) { "account B" })
		val third = state.revoke()
		assertTrue(state.commit(third) { "account A" })
		assertFalse(state.commit(first) { "old A" })
		assertEquals("account A", state.read().payload)
	}

	@Test
	fun concurrentClientsRetryWithoutLosingUpdates() {
		val state = AtomicPrivateState(0)
		val firstClient = state
		val secondClient = state
		val epoch = state.read().epoch
		val entered = CountDownLatch(2)
		val resume = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			fun increment(client: AtomicPrivateState<Int>) = executor.submit<Boolean> {
				var initial = true
				client.commit(epoch) {
					if (initial) {
						initial = false
						entered.countDown()
						resume.awaitOrFail()
					}
					it + 1
				}
			}
			val first = increment(firstClient)
			val second = increment(secondClient)
			entered.awaitOrFail()
			resume.countDown()
			assertTrue(first.get(5, TimeUnit.SECONDS))
			assertTrue(second.get(5, TimeUnit.SECONDS))
			assertEquals(2, state.read().payload)
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun blockedUpdateDoesNotBlockAnotherClientOrRevoke() {
		val state = AtomicPrivateState(0)
		val epoch = state.read().epoch
		val entered = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val blocked = executor.submit<Boolean> {
				state.commit(epoch) {
					entered.countDown()
					resume.awaitOrFail()
					it + 1
				}
			}
			entered.awaitOrFail()
			val other = executor.submit<Boolean> { state.commit(epoch) { it + 10 } }
			assertTrue(other.get(5, TimeUnit.SECONDS))
			val revoked = executor.submit { state.revoke() }
			revoked.get(5, TimeUnit.SECONDS)
			resume.countDown()
			assertFalse(blocked.get(5, TimeUnit.SECONDS))
			assertEquals(0, state.read().payload)
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}
}
