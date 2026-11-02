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

@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun tamperingAndMissingKeyPreserveBytesUntilReset() {
		val account = AccountId("synthetic-tamper")
		val coordinator = SessionCoordinator()
		val active = coordinator.activate(account)
		val cache = EncryptedCache(context, coordinator)
		cache.reset(active)
		assertTrue(cache.write(active, "synthetic-content".toByteArray()))
		val disk = File(context.filesDir, "backup-cache/${opaqueId(account.value)}")
		assertFalse(disk.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-content"))
		val corrupt = disk.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
		disk.writeBytes(corrupt)
		try { cache.read(active); fail("Tampering must fail") } catch (_: StorageUnreadableException) { }
		try { cache.write(active, byteArrayOf(3)); fail("Write must not replace unreadable data") } catch (_: StorageUnreadableException) { }
		assertArrayEquals(corrupt, disk.readBytes())
		cache.reset(active)
		cache.write(active, byteArrayOf(4))
		KeystoreKeys().delete("cache-${opaqueId(account.value)}")
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
		val coordinator = SessionCoordinator()
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
		val session = SessionStore(context)
		session.reset()
		session.write("synthetic-token".toByteArray())
		assertArrayEquals("synthetic-token".toByteArray(), session.read())
		assertFalse(File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-token"))
		session.reset()
	}
}
