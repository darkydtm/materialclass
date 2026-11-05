package org.materialclass

import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotApplyResult
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PrivacySnapshotExperimentTest {
	private data class Surface(val epoch: Long, val account: String?, val profile: String?, val diary: String?)
	private val noMerge = object : SnapshotMutationPolicy<Surface> {
		override fun equivalent(a: Surface, b: Surface) = a == b
		override fun merge(previous: Surface, current: Surface, applied: Surface): Surface? = null
	}
	private inner class Store {
		val state = mutableStateOf(Surface(0, "A", null, null), noMerge)
		val cancellationLock = Any()
		var cancelled = false
		fun cancelIo() = synchronized(cancellationLock) { cancelled = true }
		fun revoke(): SnapshotApplyResult {
			cancelIo()
			while (true) {
				val snapshot = Snapshot.takeMutableSnapshot()
				try {
					snapshot.enter { state.value = Surface(state.value.epoch + 1, null, null, null) }
					val result = snapshot.apply()
					if (result is SnapshotApplyResult.Success) return result
				} finally { snapshot.dispose() }
			}
		}
	}
	private fun await(latch: CountDownLatch) = assertTrue("barrier timed out", latch.await(10, TimeUnit.SECONDS))
	private fun privateSnapshot(store: Store, epoch: Long, diary: Boolean = false) = Snapshot.takeMutableSnapshot().also { snapshot ->
		snapshot.enter {
			val old = store.state.value
			if (old.epoch == epoch && old.account == "A") {
				store.state.value = if (diary) old.copy(diary = "private diary") else old.copy(profile = "private profile")
			}
		}
	}

	@Test fun revokeWinsAgainstBothControllers() {
		val store = Store()
		val profile = privateSnapshot(store, 0)
		val diary = privateSnapshot(store, 0, true)
		try {
			assertTrue(store.revoke() is SnapshotApplyResult.Success)
			assertTrue(profile.apply() is SnapshotApplyResult.Failure)
			assertTrue(diary.apply() is SnapshotApplyResult.Failure)
			assertEquals(Surface(1, null, null, null), store.state.value)
		} finally { profile.dispose(); diary.dispose() }
	}

	@Test fun publishWinsThenRevokeRetriesWithoutRestoringPayload() {
		val store = Store()
		val revoke = Snapshot.takeMutableSnapshot()
		val profile = privateSnapshot(store, 0)
		try {
			revoke.enter { store.state.value = Surface(1, null, null, null) }
			assertTrue(profile.apply() is SnapshotApplyResult.Success)
			assertTrue(revoke.apply() is SnapshotApplyResult.Failure)
			assertEquals("private profile", store.state.value.profile)
			assertTrue(store.revoke() is SnapshotApplyResult.Success)
			assertEquals(Surface(1, null, null, null), store.state.value)
		} finally { revoke.dispose(); profile.dispose() }
	}

	@Test fun concurrentPublishCannotApplyAfterCompletedRevoke() {
		val store = Store()
		val prepared = CountDownLatch(2)
		val release = CountDownLatch(1)
		val results = arrayOfNulls<SnapshotApplyResult>(2)
		val errors = AtomicReference<Throwable?>()
		val workers = (0..1).map { index ->
			thread {
				try {
					val snapshot = privateSnapshot(store, 0, index == 1)
					try {
						prepared.countDown()
						await(release)
						results[index] = snapshot.apply()
					} finally { snapshot.dispose() }
				} catch (t: Throwable) { errors.compareAndSet(null, t) }
			}
		}
		try {
			await(prepared)
			assertTrue(store.revoke() is SnapshotApplyResult.Success)
		} finally {
			release.countDown()
			workers.forEach { it.join(10000) }
		}
		assertNull(errors.get())
		assertTrue(results.all { it is SnapshotApplyResult.Failure })
		assertEquals(Surface(1, null, null, null), store.state.value)
	}

	@Test fun identicalConcurrentWritesAreEquivalentEvenWithoutMerge() {
		val store = Store()
		val first = privateSnapshot(store, 0)
		val second = privateSnapshot(store, 0)
		try {
			assertTrue(first.apply() is SnapshotApplyResult.Success)
			assertTrue(second.apply() is SnapshotApplyResult.Success)
			assertEquals("private profile", store.state.value.profile)
		} finally { first.dispose(); second.dispose() }
	}

	@Test fun accountAbaRejectsOldSnapshotAndEpoch() {
		val store = Store()
		val old = privateSnapshot(store, 0)
		try {
			store.revoke()
			store.state.value = Surface(2, "A", null, null)
			assertTrue(old.apply() is SnapshotApplyResult.Failure)
			val stale = privateSnapshot(store, 0)
			try { assertTrue(stale.apply() is SnapshotApplyResult.Success) } finally { stale.dispose() }
			assertEquals(Surface(2, "A", null, null), store.state.value)
		} finally { old.dispose() }
	}

	@Test fun equivalentWritesDoNotConflictButChangedEpochDoes() {
		val store = Store()
		val old = privateSnapshot(store, 0)
		try {
			store.state.value = store.state.value.copy()
			assertTrue(old.apply() is SnapshotApplyResult.Success)
			val stale = privateSnapshot(store, 0, true)
			try {
				store.state.value = store.state.value.copy(epoch = 1)
				assertTrue(stale.apply() is SnapshotApplyResult.Failure)
			} finally { stale.dispose() }
		} finally { old.dispose() }
	}

	@Test fun cancellationCompletesWhileApplyObserverBlocks() {
		val store = Store()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val done = CountDownLatch(1)
		val error = AtomicReference<Throwable?>()
		val observer = Snapshot.registerApplyObserver { changed, _ ->
			if (store.state in changed) { entered.countDown(); await(release) }
		}
		val worker = thread {
			try { store.revoke() } catch (t: Throwable) { error.set(t) } finally { done.countDown() }
		}
		try {
			await(entered)
			assertTrue(synchronized(store.cancellationLock) { store.cancelled })
			assertEquals(1L, store.state.value.epoch)
			assertEquals(1L, done.count)
		} finally { release.countDown(); worker.join(10000); observer.dispose() }
		assertNull(error.get())
		assertEquals(0L, done.count)
	}

	@Test fun observerFailureCanFollowCommittedApply() {
		val store = Store()
		val observer = Snapshot.registerApplyObserver { changed, _ ->
			if (store.state in changed) throw IllegalStateException("observer")
		}
		try {
			try { store.revoke(); fail("observer did not throw") } catch (expected: IllegalStateException) { assertEquals("observer", expected.message) }
			assertTrue(store.cancelled)
			assertEquals(Surface(1, null, null, null), store.state.value)
		} finally { observer.dispose() }
	}

	@Test fun cancellationPrecedesSnapshotApplyAndDoesNotItselfClearUi() {
		val store = Store()
		store.state.value = store.state.value.copy(profile = "private profile")
		val ready = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val done = CountDownLatch(1)
		val worker = thread {
			try { store.cancelIo(); ready.countDown(); await(proceed); store.revoke() } finally { done.countDown() }
		}
		try {
			await(ready)
			assertTrue(synchronized(store.cancellationLock) { store.cancelled })
			assertEquals("private profile", store.state.value.profile)
		} finally { proceed.countDown(); worker.join(10000) }
		assertEquals(0L, done.count)
		assertNull(store.state.value.profile)
	}
}
