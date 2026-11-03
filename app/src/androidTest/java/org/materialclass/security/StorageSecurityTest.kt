package org.materialclass.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.AccountId
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionVerification
import org.materialclass.session.SessionVerifier
import org.materialclass.session.RestoreResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	private fun resetProcessSession() {
		val active = SessionCoordinator::class.java.getDeclaredField("active")
		active.isAccessible = true
		active.set(null, null)
		val allowed = SessionCoordinator::class.java.getDeclaredField("restorationAllowed")
		allowed.isAccessible = true
		allowed.setBoolean(null, true)
	}

	@Test
	fun retryPreservesSessionButMismatchAndExpiryRevokeIt() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val account = AccountId("synthetic-verify")
		val old = coordinator.activate(account)
		store.write(old, "synthetic-material".toByteArray())
		resetProcessSession()
		assertEquals(RestoreResult.Retry, store.restore { SessionVerification.Retry })
		assertTrue(File(context.noBackupFilesDir, "session").exists())
		assertEquals(RestoreResult.Invalid, store.restore { SessionVerification.Verified(AccountId("synthetic-other"), true) })
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		val next = coordinator.activate(account)
		store.write(next, byteArrayOf(7))
		resetProcessSession()
		assertEquals(RestoreResult.Invalid, store.restore { SessionVerification.Expired })
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		val third = coordinator.activate(account)
		store.write(third, byteArrayOf(8))
		resetProcessSession()
		assertEquals(RestoreResult.Invalid, store.restore { SessionVerification.Verified(account, false) })
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		coordinator.logout()
	}

	@Test
	fun concurrentRetryCannotDiscardExpiredRestore() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val old = coordinator.activate(AccountId("synthetic-concurrent"))
		store.write(old, byteArrayOf(1))
		resetProcessSession()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val started = CountDownLatch(1)
		val finished = CountDownLatch(1)
		var expired: RestoreResult? = null
		val first = thread {
			expired = store.restore {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				SessionVerification.Expired
			}
		}
		check(entered.await(10, TimeUnit.SECONDS))
		val second = thread {
			started.countDown()
			try { SessionStore(context, SessionCoordinator(context)).restore { SessionVerification.Retry } }
			finally { finished.countDown() }
		}
		check(started.await(10, TimeUnit.SECONDS))
		finished.await(2, TimeUnit.SECONDS)
		release.countDown()
		first.join(10000)
		second.join(10000)
		assertFalse(first.isAlive)
		assertFalse(second.isAlive)
		assertEquals(RestoreResult.Invalid, expired)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		coordinator.logout()
	}

	@Test
	fun expiredRestoreCannotRevokeNewLoginWithSameAccount() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val account = AccountId("synthetic-same-account")
		val old = coordinator.activate(account)
		store.write(old, byteArrayOf(1))
		resetProcessSession()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		var result: RestoreResult? = null
		val worker = thread {
			result = store.restore {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				SessionVerification.Expired
			}
		}
		check(entered.await(10, TimeUnit.SECONDS))
		val newer = coordinator.activate(account)
		assertTrue(store.write(newer, byteArrayOf(2)))
		release.countDown()
		worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		assertArrayEquals(byteArrayOf(2), store.read(newer))
		coordinator.logout()
	}

	@Test
	fun logoutCancelsPendingVerification() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val account = AccountId("synthetic-race")
		val old = coordinator.activate(account)
		store.write(old, byteArrayOf(1))
		resetProcessSession()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		var result: RestoreResult? = null
		val worker = thread {
			result = store.restore {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				SessionVerification.Verified(account, true)
			}
		}
		check(entered.await(10, TimeUnit.SECONDS))
		coordinator.logout()
		release.countDown()
		worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun storedIdentityAloneCannotRestoreSession() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val active = coordinator.activate(AccountId("synthetic-unverified"))
		store.write(active, "synthetic-token".toByteArray())
		val field = SessionCoordinator::class.java.getDeclaredField("active")
		field.isAccessible = true
		field.set(null, null)
		val allowed = SessionCoordinator::class.java.getDeclaredField("restorationAllowed")
		allowed.isAccessible = true
		allowed.setBoolean(null, true)
		try {
			assertNull("A stored identity is not independent verification", store.restore())
		} finally {
			coordinator.logout()
		}
	}

	@Test
	fun restoresEncryptedSessionAfterProcessRecreation() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val old = coordinator.activate(AccountId("synthetic-restored-account"))
		assertTrue(store.write(old, "synthetic-restored-token".toByteArray()))
		val cache = EncryptedCache(context, coordinator)
		cache.write(old, byteArrayOf(3, 4))
		val bytes = File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1)
		assertFalse(bytes.contains("synthetic-restored-account"))
		assertFalse(bytes.contains("synthetic-restored-token"))
		val active = SessionCoordinator::class.java.getDeclaredField("active")
		active.isAccessible = true
		active.set(null, null)
		val allowed = SessionCoordinator::class.java.getDeclaredField("restorationAllowed")
		allowed.isAccessible = true
		allowed.setBoolean(null, true)
		val restarted = SessionCoordinator(context)
		val restoredStore = SessionStore(context, restarted)
		val restore = SessionStore::class.java.getMethod("restore")
		val result = restoredStore.restore(SessionVerifier { material ->
			assertArrayEquals("synthetic-restored-token".toByteArray(), material)
			SessionVerification.Verified(AccountId("synthetic-restored-account"), true)
		})
		val restored = (result as RestoreResult.Restored).session
		assertArrayEquals("synthetic-restored-token".toByteArray(), restoredStore.read(restored))
		assertArrayEquals(byteArrayOf(3, 4), EncryptedCache(context, restarted).read(restored))
		assertNull(restore.invoke(restoredStore))
		assertNull(restarted.ifActive(old) { "stale" })
		val emitted = mutableListOf<String>()
		restarted.logout()
		assertNull(restarted.ifActive(restored) { emitted.add("stale") })
		assertTrue(emitted.isEmpty())
		assertNull(restore.invoke(restoredStore))
		assertFalse(store.write(old, byteArrayOf(9)))
		cache.clearAll()
	}

	@Test
	fun logoutPersistsRevocationBeforeSessionRemoval() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val session = coordinator.activate(AccountId("synthetic-marker"))
		store.write(session, byteArrayOf(1))
		coordinator.logout()
		assertTrue(File(context.noBackupFilesDir, "session-revoked").exists())
	}

	@Test
	fun durableRevocationMarkerPreventsRestoreAfterCrash() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val session = coordinator.activate(AccountId("synthetic-revoked"))
		store.write(session, byteArrayOf(1))
		File(context.noBackupFilesDir, "session-revoked").writeBytes(byteArrayOf(1))
		val active = SessionCoordinator::class.java.getDeclaredField("active")
		active.isAccessible = true
		active.set(null, null)
		val allowed = SessionCoordinator::class.java.getDeclaredField("restorationAllowed")
		allowed.isAccessible = true
		allowed.setBoolean(null, true)
		var verifierCalled = false
		try {
			assertEquals(RestoreResult.Absent, SessionStore(context, SessionCoordinator(context)).restore {
				verifierCalled = true
				SessionVerification.Retry
			})
			assertFalse(verifierCalled)
		} finally {
			coordinator.logout()
		}
	}

	@Test
	fun logoutDeletesSessionKeyWhenTemporaryDirectoryIsBlocked() {
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		val session = coordinator.activate(AccountId("synthetic-blocked-temporary"))
		store.write(session, byteArrayOf(1))
		val temporary = File(context.noBackupFilesDir, "storage-tmp")
		check(temporary.delete())
		temporary.writeBytes(byteArrayOf(1))
		try {
			try { coordinator.logout() } catch (_: Exception) { }
			try { KeystoreKeys().key("session", false); fail("Session key must be deleted") } catch (_: StorageUnreadableException) { }
		} finally {
			temporary.delete()
			coordinator.logout()
		}
	}

	@Test
	fun clearAllDestroysCacheKeysAfterFileDeletionFailure() {
		val cache = EncryptedCache(context, SessionCoordinator(context))
		cache.clearAll()
		val keys = KeystoreKeys()
		keys.key("cache-synthetic-clear-failure", true)
		val directory = File(context.filesDir, "backup-cache/synthetic-blocked")
		check(directory.mkdir())
		val child = File(directory, "child")
		child.writeBytes(byteArrayOf(1))
		try {
			try { cache.clearAll(); fail("Failure must be reported") } catch (_: java.io.IOException) { }
			try { keys.key("cache-synthetic-clear-failure", false); fail("Cache key must be deleted") } catch (_: StorageUnreadableException) { }
		} finally {
			child.delete()
			directory.delete()
			cache.clearAll()
		}
	}

	@Test
	fun failedFileDeletionStillDestroysKey() {
		val alias = "synthetic-delete-failure"
		val keys = KeystoreKeys()
		keys.delete(alias)
		keys.key(alias, true)
		val directory = File(context.noBackupFilesDir, "synthetic-delete-failure")
		directory.mkdirs()
		val child = File(directory, "child")
		child.writeBytes(byteArrayOf(1))
		try {
			EncryptedFile(directory, alias, keys, org.materialclass.cache.CacheFiles(context)).reset()
			fail("Deletion failure must be reported")
		} catch (_: java.io.IOException) { }
		try {
			keys.key(alias, false)
			fail("Key must be destroyed even when file deletion fails")
		} catch (_: StorageUnreadableException) { }
		child.delete()
		directory.delete()
	}

	@Test
	fun wrongKeyAndInterruptedTemporaryFileDoNotReplaceCache() {
		val coordinator = SessionCoordinator(context)
		val cache = EncryptedCache(context, coordinator)
		cache.clearAll()
		val account = AccountId("synthetic-wrong-key")
		val session = coordinator.activate(account)
		cache.write(session, byteArrayOf(4, 5))
		val id = KeystoreKeys().namespace(account.value, false)
		val disk = File(context.filesDir, "backup-cache/$id")
		val bytes = disk.readBytes()
		val tempDir = File(context.noBackupFilesDir, "storage-tmp")
		val interrupted = File(tempDir, "synthetic-interrupted.tmp")
		interrupted.writeBytes(byteArrayOf(1, 2, 3))
		assertArrayEquals(byteArrayOf(4, 5), EncryptedCache(context, SessionCoordinator(context)).read(session))
		KeystoreKeys().delete("cache-$id")
		KeystoreKeys().key("cache-$id", true)
		try { cache.write(session, byteArrayOf(6)); fail("Wrong key must fail") } catch (_: StorageUnreadableException) { }
		assertArrayEquals(bytes, disk.readBytes())
		assertTrue(interrupted.delete())
		cache.clearAll()
		coordinator.logout()
	}

	@Test
	fun restoredCacheWithoutNamespaceKeyIsPreservedUntilAnonymousReset() {
		val coordinator = SessionCoordinator(context)
		val cache = EncryptedCache(context, coordinator)
		cache.clearAll()
		val account = AccountId("synthetic-restored")
		val active = coordinator.activate(account)
		cache.write(active, byteArrayOf(8))
		val directory = File(context.filesDir, "backup-cache")
		val files = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
		val predictable = MessageDigest.getInstance("SHA-256").digest(account.value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
		assertFalse(files.containsKey(predictable))
		KeystoreKeys().delete("cache-namespace")
		try { cache.write(active, byteArrayOf(9)); fail("Missing namespace must fail") } catch (_: StorageUnreadableException) { }
		assertEquals(files, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
		coordinator.logout()
		cache.clearAll()
		assertTrue(directory.listFiles()!!.isEmpty())
	}

	@Test
	fun delayedSessionWriteCannotResurrectLogoutAcrossInstances() {
		val first = SessionCoordinator(context)
		val second = SessionCoordinator(context)
		val old = first.activate(AccountId("synthetic-delay"))
		val store = SessionStore(context, first)
		assertTrue(store.write(old, byteArrayOf(1)))
		val ready = CountDownLatch(1)
		val release = CountDownLatch(1)
		var wrote = true
		val worker = thread {
			ready.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			wrote = store.write(old, byteArrayOf(2))
		}
		check(ready.await(10, TimeUnit.SECONDS))
		second.logout()
		release.countDown()
		worker.join(10000)
		assertFalse(worker.isAlive)
		assertFalse(wrote)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun tamperingAndMissingKeyPreserveBytesUntilReset() {
		val account = AccountId("synthetic-tamper")
		val coordinator = SessionCoordinator(context)
		val active = coordinator.activate(account)
		val cache = EncryptedCache(context, coordinator)
		cache.reset(active)
		assertTrue(cache.write(active, "synthetic-content".toByteArray()))
		val id = KeystoreKeys().namespace(account.value, false)
		val disk = File(context.filesDir, "backup-cache/$id")
		assertFalse(disk.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-content"))
		val corrupt = disk.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
		disk.writeBytes(corrupt)
		try { cache.read(active); fail("Tampering must fail") } catch (_: StorageUnreadableException) { }
		try { cache.write(active, byteArrayOf(3)); fail("Write must not replace unreadable data") } catch (_: StorageUnreadableException) { }
		assertArrayEquals(corrupt, disk.readBytes())
		cache.reset(active)
		cache.write(active, byteArrayOf(4))
		KeystoreKeys().delete("cache-$id")
		val original = disk.readBytes()
		try { cache.read(active); fail("Missing key must fail") } catch (_: StorageUnreadableException) { }
		try { cache.write(active, byteArrayOf(5)); fail("Missing key must not overwrite") } catch (_: StorageUnreadableException) { }
		assertArrayEquals(original, disk.readBytes())
		cache.reset(active)
		cache.write(active, byteArrayOf(6))
		assertArrayEquals(byteArrayOf(6), cache.read(active))
		cache.reset(active)
	}

	@Test
	fun isolatesAccountsAndSessions() {
		val coordinator = SessionCoordinator(context)
		val cache = EncryptedCache(context, coordinator)
		val a = coordinator.activate(AccountId("synthetic-a"))
		cache.reset(a)
		cache.write(a, byteArrayOf(1))
		coordinator.logout()
		val b = coordinator.activate(AccountId("synthetic-b"))
		cache.reset(b)
		assertNull(cache.read(a))
		assertFalse(cache.write(a, byteArrayOf(9)))
		assertNull(cache.read(b))
		cache.write(b, byteArrayOf(2))
		cache.reset(b)
		val aAgain = coordinator.activate(AccountId("synthetic-a"))
		assertArrayEquals(byteArrayOf(1), cache.read(aAgain))
		cache.reset(aAgain)
		val session = SessionStore(context, coordinator)
		session.write(aAgain, "synthetic-token".toByteArray())
		assertArrayEquals("synthetic-token".toByteArray(), session.read(aAgain))
		assertFalse(File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-token"))
		coordinator.logout()
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		assertFalse(session.write(aAgain, byteArrayOf(7)))
	}
}
