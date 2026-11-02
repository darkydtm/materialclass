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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
