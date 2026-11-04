package org.materialclass.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanupRecoveryTest {
	@Test
	fun failedJournalReadOrConflictPreservesCredential() {
		for (conflict in listOf(false, true)) {
			var cleared = false
			val journal = object : CleanupJournal {
				override fun read(): CacheNamespace? {
					if (!conflict) throw IllegalStateException()
					return CacheNamespace("b".repeat(64))
				}
				override fun write(namespace: CacheNamespace) { throw AssertionError("Unexpected write") }
				override fun clear() { throw AssertionError("Unexpected clear") }
			}
			try { CleanupRecovery(journal, { cleared = true }, { cleared = true }).cleanup(CacheNamespace("a".repeat(64))); throw AssertionError("Expected failure") } catch (_: LogoutStorageException) { }
			assertEquals(false, cleared)
		}
	}

	@Test
	fun retryResyncsExistingJournalBeforeDeletingCredential() {
		val namespace = CacheNamespace("a".repeat(64))
		var saved: CacheNamespace? = null
		var fail = true
		var cleared = false
		val journal = object : CleanupJournal {
			override fun read() = saved
			override fun write(namespace: CacheNamespace) { saved = namespace; if (fail) throw IllegalStateException() }
			override fun clear() { saved = null }
		}
		val recovery = CleanupRecovery(journal, {}, { cleared = true })
		try { recovery.cleanup(namespace); throw AssertionError("Expected failure") } catch (_: LogoutStorageException) { }
		assertEquals(false, cleared)
		try { recovery.recover(); throw AssertionError("Expected failure") } catch (_: IllegalStateException) { }
		assertEquals(false, cleared)
		fail = false
		recovery.recover()
		assertEquals(true, cleared)
		assertNull(saved)
	}

	@Test
	fun failedCleanupIsRetriedAfterRecreation() {
		var saved: CacheNamespace? = null
		var fail = true
		val journal = object : CleanupJournal {
			override fun read() = saved
			override fun write(namespace: CacheNamespace) { saved = namespace }
			override fun clear() { saved = null }
		}
		val namespace = CacheNamespace("a".repeat(64))
		val first = CleanupRecovery(journal, { if (fail) throw IllegalStateException() }, {})
		try { first.cleanup(namespace); throw AssertionError("Expected failure") } catch (_: IllegalStateException) { }
		assertEquals(namespace, saved)
		fail = false
		val cleared = mutableListOf<CacheNamespace>()
		CleanupRecovery(journal, { cleared.add(it) }, {}).recover()
		assertEquals(listOf(namespace), cleared)
		assertNull(saved)
	}

	@Test
	fun eachCacheFailureKeepsJournalForScopedRetry() {
		for (failed in listOf("diary-file", "diary-key", "schedule-file", "schedule-key")) {
			var saved: CacheNamespace? = null
			val journal = object : CleanupJournal {
				override fun read() = saved
				override fun write(namespace: CacheNamespace) { saved = namespace }
				override fun clear() { saved = null }
			}
			val old = CacheNamespace("a".repeat(64))
			val other = CacheNamespace("b".repeat(64))
			val entries = mutableSetOf<Pair<CacheNamespace, String>>()
			for (namespace in listOf(old, other)) for (part in listOf("diary-file", "diary-key", "schedule-file", "schedule-key")) entries.add(namespace to part)
			var fail = true
			val clear: (CacheNamespace) -> Unit = { namespace ->
				var error = false
				for (part in listOf("diary-file", "diary-key", "schedule-file", "schedule-key")) {
					if (part == failed && fail) error = true else entries.remove(namespace to part)
				}
				if (error) throw IllegalStateException()
			}
			try { CleanupRecovery(journal, clear, {}).cleanup(old); throw AssertionError("Expected failure") } catch (_: IllegalStateException) { }
			assertEquals(old, saved)
			fail = false
			CleanupRecovery(journal, clear, {}).recover()
			assertNull(saved)
			assertEquals(4, entries.size)
			assertEquals(setOf(other), entries.map { it.first }.toSet())
		}
	}

	@Test
	fun failedJournalWriteDoesNotAttemptCacheCleanup() {
		var sessionCleared = false
		var cacheCleared = false
		val journal = object : CleanupJournal {
			override fun read(): CacheNamespace? = null
			override fun write(namespace: CacheNamespace) { throw IllegalStateException() }
			override fun clear() { }
		}
		try {
			CleanupRecovery(journal, { cacheCleared = true }, { sessionCleared = true }).cleanup(CacheNamespace("a".repeat(64)))
			throw AssertionError("Expected failure")
		} catch (_: LogoutStorageException) { }
		assertEquals(false, sessionCleared)
		assertEquals(false, cacheCleared)
	}

	@Test
	fun loginWaitsForRecoveryAndCannotOutrunFailedRetry() {
		val namespace = CacheNamespace("a".repeat(64))
		var saved: CacheNamespace? = namespace
		val entered = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		val journal = object : CleanupJournal {
			override fun read() = saved
			override fun write(namespace: CacheNamespace) { saved = namespace }
			override fun clear() { saved = null }
		}
		val coordinator = SessionCoordinator({}, {
			entered.countDown()
			check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
			throw IllegalStateException("synthetic failure")
		}, journal)
		val finished = java.util.concurrent.CountDownLatch(1)
		var failure: Throwable? = null
		val worker = kotlin.concurrent.thread {
			try { coordinator.beginLogin() } catch (error: Throwable) { failure = error } finally { finished.countDown() }
		}
		try {
			assertEquals(true, entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
			assertEquals(false, finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
			release.countDown()
			assertEquals(true, finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
			assertEquals(true, failure is IllegalStateException)
			assertEquals(namespace, saved)
		} finally { release.countDown(); worker.join() }
	}

	@Test
	fun failureToClearSessionRetainsJournal() {
		var saved: CacheNamespace? = null
		val journal = object : CleanupJournal {
			override fun read() = saved
			override fun write(namespace: CacheNamespace) { saved = namespace }
			override fun clear() { saved = null }
		}
		val namespace = CacheNamespace("a".repeat(64))
		try { CleanupRecovery(journal, {}, { throw IllegalStateException() }).cleanup(namespace); throw AssertionError("Expected failure") } catch (_: IllegalStateException) { }
		assertEquals(namespace, saved)
	}
}
