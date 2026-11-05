package org.materialclass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun interface DirtySubscriber {
	fun onDirty()
}

private class Session
private class SessionStore {
	var payload: String? = null
}
private class Registration(val callback: DirtySubscriber)

private class ActorStore(private val dispatcher: Executor) {
	private val lock = Any()
	private var session: Session? = null
	private var store: SessionStore? = null
	private var subscriber: Registration? = null
	private var dirty = false
	private var scheduled = false
	private var closed = false

	fun switchAccount(): Session {
		val result = synchronized(lock) {
			check(!closed)
			Session().also {
				session = it
				store = SessionStore()
				dirty = true
			}
		}
		retry()
		return result
	}

	fun publish(token: Session, value: String): Boolean {
		val accepted = synchronized(lock) {
			if (closed || token !== session) false else {
				store!!.payload = value
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
			session = null
			store = null
			dirty = true
		}
		retry()
	}

	fun read(): String? = synchronized(lock) { store?.payload }

	fun subscribe(value: DirtySubscriber): AutoCloseable {
		val registration = Registration(value)
		synchronized(lock) {
			check(!closed)
			check(subscriber == null)
			subscriber = registration
			dirty = true
		}
		retry()
		return AutoCloseable {
			synchronized(lock) {
				if (subscriber === registration) subscriber = null
			}
		}
	}

	fun retry(): Boolean {
		val submit = synchronized(lock) {
			if (closed || subscriber == null || !dirty || scheduled) false else {
				scheduled = true
				true
			}
		}
		if (!submit) return false
		return try {
			dispatcher.execute { drain() }
			true
		} catch (_: RejectedExecutionException) {
			synchronized(lock) { scheduled = false }
			false
		}
	}

	private fun drain() {
		while (true) {
			val target = synchronized(lock) {
				if (closed || !dirty || subscriber == null) {
					scheduled = false
					null
				} else {
					dirty = false
					subscriber
				}
			} ?: return
			try {
				target.callback.onDirty()
			} catch (_: RuntimeException) {
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
		session = null
		store = null
		subscriber = null
		dirty = false
	}
}

class PrivacyActorSpikeTest {
	@Test
	fun subscriptionReadsCurrentStoreAndResubscribes() {
		val tasks = mutableListOf<Runnable>()
		val store = ActorStore(Executor { tasks.add(it) })
		val token = store.switchAccount()
		assertTrue(store.publish(token, "first"))
		val seen = mutableListOf<String?>()
		val first = store.subscribe(DirtySubscriber { seen.add(store.read()) })
		assertTrue(store.publish(token, "latest"))
		tasks.removeAt(0).run()
		assertEquals(listOf("latest"), seen)
		first.close()
		store.revoke()
		val second = store.subscribe(DirtySubscriber { seen.add(store.read()) })
		tasks.removeAt(0).run()
		assertEquals(listOf("latest", null), seen)
		second.close()
		store.close()
	}

	@Test
	fun blockedObserverDoesNotDelayChangesAndCoalescesNotifications() {
		val executor = Executors.newSingleThreadExecutor()
		val other = Executors.newSingleThreadExecutor()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val delivered = CountDownLatch(2)
		val calls = AtomicInteger()
		val store = ActorStore(executor)
		store.subscribe(DirtySubscriber {
			if (calls.incrementAndGet() == 1) {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			}
			delivered.countDown()
		})
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS))
			val token = store.switchAccount()
			other.submit {
				repeat(10000) { assertTrue(store.publish(token, "value")) }
				store.revoke()
			}.get(5, TimeUnit.SECONDS)
			assertNull(store.read())
			assertFalse(store.publish(token, "late"))
			assertEquals(1, calls.get())
			release.countDown()
			assertTrue(delivered.await(5, TimeUnit.SECONDS))
			assertEquals(2, calls.get())
		} finally {
			release.countDown()
			store.close()
			other.shutdownNow()
			executor.shutdownNow()
		}
	}

	@Test
	fun publishAndRevokeLinearizeInBothOrdersAndRejectAba() {
		val executor = Executors.newSingleThreadExecutor()
		val store = ActorStore(Executor { })
		try {
			val first = store.switchAccount()
			val published = CountDownLatch(1)
			val revoke = executor.submit {
				check(published.await(5, TimeUnit.SECONDS))
				store.revoke()
			}
			assertTrue(store.publish(first, "before"))
			published.countDown()
			revoke.get(5, TimeUnit.SECONDS)
			assertNull(store.read())
			val next = store.switchAccount()
			val revoked = CountDownLatch(1)
			val late = executor.submit<Boolean> {
				check(revoked.await(5, TimeUnit.SECONDS))
				store.publish(next, "after")
			}
			store.revoke()
			revoked.countDown()
			assertFalse(late.get(5, TimeUnit.SECONDS))
			val replacement = store.switchAccount()
			assertFalse(store.publish(first, "aba"))
			assertFalse(store.publish(next, "aba"))
			assertTrue(store.publish(replacement, "current"))
			assertEquals("current", store.read())
		} finally {
			store.close()
			executor.shutdownNow()
		}
	}

	@Test
	fun rejectedDispatchKeepsDirtyForDelayedRetry() {
		val attempts = AtomicInteger()
		val tasks = mutableListOf<Runnable>()
		val dispatcher = Executor { task ->
			if (attempts.incrementAndGet() == 1) throw RejectedExecutionException()
			tasks.add(task)
		}
		val store = ActorStore(dispatcher)
		val seen = mutableListOf<String?>()
		val token = store.switchAccount()
		assertTrue(store.publish(token, "before"))
		store.subscribe(DirtySubscriber { seen.add(store.read()) })
		assertTrue(seen.isEmpty())
		assertEquals(1, attempts.get())
		assertTrue(store.retry())
		assertTrue(store.publish(token, "after"))
		assertEquals(1, tasks.size)
		tasks.single().run()
		assertEquals(listOf("after"), seen)
		assertFalse(store.retry())
		store.close()
	}

	@Test
	fun failedSubscriberKeepsDirtyForRetry() {
		val tasks = mutableListOf<Runnable>()
		val calls = AtomicInteger()
		val store = ActorStore(Executor { tasks.add(it) })
		store.subscribe(DirtySubscriber {
			if (calls.incrementAndGet() == 1) error("failure")
		})
		tasks.removeAt(0).run()
		assertTrue(store.retry())
		tasks.removeAt(0).run()
		assertEquals(2, calls.get())
		assertFalse(store.retry())
		store.close()
	}

	@Test
	fun closeRevokesAndCancelsQueuedNotifications() {
		val tasks = mutableListOf<Runnable>()
		val calls = AtomicInteger()
		val store = ActorStore(Executor { tasks.add(it) })
		store.subscribe(DirtySubscriber { calls.incrementAndGet() })
		val token = store.switchAccount()
		assertTrue(store.publish(token, "private"))
		store.close()
		assertNull(store.read())
		assertFalse(store.publish(token, "late"))
		assertFalse(store.retry())
		tasks.single().run()
		assertEquals(0, calls.get())
		assertThrows(IllegalStateException::class.java) { store.subscribe(DirtySubscriber {}) }
	}

	@Test
	fun oldSnapshotReadsCurrentStoreButSavedCopySurvives() {
		val state = mutableStateOf(Unit, neverEqualPolicy())
		val executor = Executors.newSingleThreadExecutor()
		val store = ActorStore(executor)
		store.subscribe(DirtySubscriber { state.value = Unit; Snapshot.sendApplyNotifications() })
		val old = Snapshot.takeSnapshot()
		try {
			val token = store.switchAccount()
			assertTrue(store.publish(token, "private"))
			val copy = old.enter { state.value; store.read() }
			assertEquals("private", copy)
			store.revoke()
			assertNull(old.enter { state.value; store.read() })
			assertEquals("private", copy)
		} finally {
			old.dispose()
			store.close()
			executor.shutdownNow()
		}
	}

	@Test
	fun blockingExecutorPreventsNonblockingSubmission() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		val store = ActorStore(Executor {
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
		})
		try {
			val subscribe = executor.submit { store.subscribe(DirtySubscriber {}) }
			assertTrue(entered.await(5, TimeUnit.SECONDS))
			assertFalse(subscribe.isDone)
			val token = store.switchAccount()
			assertTrue(store.publish(token, "available"))
			assertEquals("available", store.read())
			store.revoke()
			assertNull(store.read())
			release.countDown()
			subscribe.get(5, TimeUnit.SECONDS)
		} finally {
			release.countDown()
			store.close()
			executor.shutdownNow()
		}
	}
}
