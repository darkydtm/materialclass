package org.materialclass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class ActorStore(private val dispatcher: Executor, private val notify: () -> Unit) {
	private val lock = Any()
	private var generation = 0L
	private var active = false
	private var payload: String? = null
	private var dirty = false
	private var scheduled = false
	private var closed = false

	fun switchAccount(): Long {
		val token = synchronized(lock) {
			check(!closed)
			generation++
			active = true
			payload = null
			dirty = true
			generation
		}
		retry()
		return token
	}

	fun publish(token: Long, value: String): Boolean {
		val accepted = synchronized(lock) {
			if (closed || !active || token != generation) false else {
				payload = value
				dirty = true
				true
			}
		}
		if (accepted) retry()
		return accepted
	}

	fun revoke() {
		synchronized(lock) {
			check(!closed)
			generation++
			active = false
			payload = null
			dirty = true
		}
		retry()
	}

	fun read(): String? = synchronized(lock) { payload }

	fun retry(): Boolean {
		val submit = synchronized(lock) {
			if (closed || !dirty || scheduled) false else {
				scheduled = true
				true
			}
		}
		if (!submit) return false
		return try {
			dispatcher.execute { drain() }
			true
		} catch (_: RuntimeException) {
			synchronized(lock) { scheduled = false }
			false
		}
	}

	private fun drain() {
		while (true) {
			val work = synchronized(lock) {
				if (closed || !dirty) {
					scheduled = false
					false
				} else {
					dirty = false
					true
				}
			}
			if (!work) return
			try {
				notify()
			} catch (_: Throwable) {
				synchronized(lock) {
					dirty = true
					scheduled = false
				}
				return
			}
		}
	}

	fun close() = synchronized(lock) {
		closed = true
		generation++
		active = false
		payload = null
		dirty = false
	}
}

class PrivacyActorSpikeTest {
	@Test
	fun blockedObserverDoesNotDelayRevokeAndCoalescesChanges() {
		val executor = Executors.newSingleThreadExecutor()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val delivered = CountDownLatch(2)
		val calls = AtomicInteger()
		val store = ActorStore(executor) {
			if (calls.incrementAndGet() == 1) {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			}
			delivered.countDown()
		}
		try {
			val token = store.switchAccount()
			assertTrue(entered.await(5, TimeUnit.SECONDS))
			repeat(10000) { assertTrue(store.publish(token, "value")) }
			val other = Executors.newSingleThreadExecutor()
			try {
				other.submit { store.revoke() }.get(5, TimeUnit.SECONDS)
			} finally {
				other.shutdownNow()
			}
			assertNull(store.read())
			assertFalse(store.publish(token, "late"))
			assertEquals(1, calls.get())
			release.countDown()
			assertTrue(delivered.await(5, TimeUnit.SECONDS))
			assertEquals(2, calls.get())
		} finally {
			release.countDown()
			store.close()
			executor.shutdownNow()
		}
	}

	@Test
	fun oldSnapshotReadsCurrentStoreButSavedCopySurvives() {
		val state = mutableStateOf(Unit, neverEqualPolicy())
		val executor = Executors.newSingleThreadExecutor()
		val store = ActorStore(executor) { state.value = Unit; Snapshot.sendApplyNotifications() }
		val old = Snapshot.takeSnapshot()
		try {
			val token = store.switchAccount()
			assertTrue(store.publish(token, "private"))
			val copy = old.enter { state.value; store.read() }
			assertEquals("private", copy)
			store.revoke()
			assertNull(old.enter { state.value; store.read() })
			assertEquals("private", copy)
			assertNull(store.read())
		} finally {
			old.dispose()
			store.close()
			executor.shutdownNow()
		}
	}

	@Test
	fun rejectedDispatchRetainsDirtyUntilExplicitRetry() {
		val attempts = AtomicInteger()
		val calls = AtomicInteger()
		val dispatcher = Executor { task ->
			if (attempts.incrementAndGet() == 1) throw java.util.concurrent.RejectedExecutionException()
			task.run()
		}
		val store = ActorStore(dispatcher) { calls.incrementAndGet() }
		store.switchAccount()
		assertEquals(0, calls.get())
		assertTrue(store.retry())
		assertEquals(1, calls.get())
		assertFalse(store.retry())
		store.close()
	}

	@Test
	fun failedObserverRetainsDirtyUntilExplicitRetry() {
		val calls = AtomicInteger()
		val store = ActorStore(Executor { it.run() }) {
			if (calls.incrementAndGet() == 1) error("failure")
		}
		store.switchAccount()
		assertEquals(1, calls.get())
		assertTrue(store.retry())
		assertEquals(2, calls.get())
		assertFalse(store.retry())
		store.close()
	}

	@Test
	fun closedStoreRevokesAndRejectsFurtherWork() {
		val tasks = mutableListOf<Runnable>()
		val calls = AtomicInteger()
		val store = ActorStore(Executor { tasks.add(it) }) { calls.incrementAndGet() }
		val token = store.switchAccount()
		assertTrue(store.publish(token, "private"))
		store.close()
		assertNull(store.read())
		assertFalse(store.publish(token, "late"))
		assertFalse(store.retry())
		tasks.single().run()
		assertEquals(0, calls.get())
	}
}
