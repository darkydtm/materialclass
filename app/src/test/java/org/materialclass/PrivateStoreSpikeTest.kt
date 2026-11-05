package org.materialclass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class PrivateStore {
	private val lock = Any()
	private var generation = 0L
	private var active = false
	private var payload: String? = null
	val invalidation = mutableStateOf(Unit, neverEqualPolicy())

	fun switchAccount(): Long {
		val token = synchronized(lock) {
			generation++
			active = true
			payload = null
			generation
		}
		notifyChange()
		return token
	}

	fun publish(token: Long, value: String): Boolean {
		val accepted = synchronized(lock) {
			if (!active || token != generation) false else {
				payload = value
				true
			}
		}
		if (accepted) notifyChange()
		return accepted
	}

	fun revoke() {
		synchronized(lock) {
			generation++
			active = false
			payload = null
		}
		notifyChange()
	}

	fun read(): String? = synchronized(lock) { payload }

	private fun notifyChange() {
		invalidation.value = Unit
		Snapshot.sendApplyNotifications()
	}
}

private class PrivateController(private val store: PrivateStore) {
	fun read(): String? {
		store.invalidation.value
		return store.read()
	}
}

class PrivateStoreSpikeTest {
	@Test
	fun oldSnapshotAndTwoControllersReadRevokedStore() {
		val store = PrivateStore()
		val first = PrivateController(store)
		val second = PrivateController(store)
		val token = store.switchAccount()
		assertTrue(store.publish(token, "account-a"))
		assertEquals("account-a", first.read())
		assertEquals("account-a", second.read())
		val old = Snapshot.takeSnapshot()
		try {
			assertEquals("account-a", old.enter { first.read() })
			val priorCopy = first.read()
			store.revoke()
			assertEquals("account-a", priorCopy)
			assertNull(first.read())
			assertNull(second.read())
			assertNull(old.enter { first.read() })
			assertNull(old.enter { second.read() })
			assertFalse(store.publish(token, "resurrected"))
			assertNull(first.read())
			val next = store.switchAccount()
			assertNull(second.read())
			assertFalse(store.publish(token, "old-account"))
			assertTrue(store.publish(next, "account-b"))
			assertEquals("account-b", first.read())
			assertEquals("account-b", old.enter { second.read() })
		} finally {
			old.dispose()
		}
	}

	@Test
	fun delayedPublisherCannotRestoreRevokedOrSwitchedAccount() {
		val store = PrivateStore()
		val first = PrivateController(store)
		val second = PrivateController(store)
		val token = store.switchAccount()
		assertTrue(store.publish(token, "before"))
		val ready = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val delayed = executor.submit<Boolean> {
				ready.countDown()
				check(resume.await(10, TimeUnit.SECONDS))
				store.publish(token, "late")
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS))
			assertEquals("before", first.read())
			store.revoke()
			assertNull(first.read())
			assertNull(second.read())
			val next = store.switchAccount()
			assertNull(first.read())
			assertTrue(store.publish(next, "new"))
			assertEquals("new", second.read())
			resume.countDown()
			assertFalse(delayed.get(5, TimeUnit.SECONDS))
			assertEquals("new", first.read())
			assertEquals("new", second.read())
		} finally {
			resume.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun blockedApplyObserverDoesNotHoldStoreLock() {
		val store = PrivateStore()
		val first = PrivateController(store)
		val second = PrivateController(store)
		val token = store.switchAccount()
		assertTrue(store.publish(token, "before"))
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		val handle = Snapshot.registerApplyObserver { changed, _ ->
			if (store.invalidation in changed && Thread.currentThread().name == "blocked-apply") {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			}
		}
		try {
			val publishing = executor.submit<Boolean> {
				Thread.currentThread().name = "blocked-apply"
				store.publish(token, "intermediate")
			}
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertEquals("intermediate", first.read())
			assertEquals("intermediate", second.read())
			executor.submit { store.revoke() }.get(5, TimeUnit.SECONDS)
			assertNull(first.read())
			assertNull(second.read())
			release.countDown()
			assertTrue(publishing.get(5, TimeUnit.SECONDS))
			assertNull(first.read())
			assertNull(second.read())
		} finally {
			release.countDown()
			handle.dispose()
			executor.shutdownNow()
		}
	}

	@Test
	fun blockedSnapshotObserverDoesNotBlockOtherControllersOrRevoke() {
		val store = PrivateStore()
		val first = PrivateController(store)
		val second = PrivateController(store)
		val token = store.switchAccount()
		assertTrue(store.publish(token, "initial"))
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		val handle = Snapshot.registerGlobalWriteObserver {
			if (it === store.invalidation && Thread.currentThread().name == "blocked-publisher") {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			}
		}
		try {
			val published = executor.submit<Boolean> {
				Thread.currentThread().name = "blocked-publisher"
				store.publish(token, "intermediate")
			}
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertEquals("intermediate", first.read())
			assertEquals("intermediate", second.read())
			val revoked = executor.submit { store.revoke() }
			revoked.get(5, TimeUnit.SECONDS)
			assertNull(first.read())
			assertNull(second.read())
			assertFalse(store.publish(token, "late"))
			release.countDown()
			assertTrue(published.get(5, TimeUnit.SECONDS))
			assertNull(first.read())
			assertNull(second.read())
		} finally {
			release.countDown()
			handle.dispose()
			executor.shutdownNow()
		}
	}
}
