package org.materialclass.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
