package org.materialclass.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.AccountId
import org.materialclass.session.RestoreResult
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionVerification
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupTransportFixtureTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val nonce = requireNotNull(InstrumentationRegistry.getArguments().getString("nonce"))

	@Test
	fun seed() {
		assertTrue(context.getSharedPreferences("appearance", 0).edit()
			.putString("theme", "black").putString("language", "kk").putString("backup_nonce", nonce).commit())
		val coordinator = SessionCoordinator(context)
		val session = coordinator.activate(AccountId("synthetic-backup-account"))
		assertTrue(SessionStore(context, coordinator).write(session, "synthetic-backup-token-$nonce".toByteArray()))
		assertTrue(EncryptedCache(context, coordinator).write(session, "synthetic-backup-cache-$nonce".toByteArray()))
		val sessionFile = File(context.noBackupFilesDir, "session")
		assertTrue(sessionFile.isFile)
		assertFalse(sessionFile.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-backup-token"))
		val entries = File(context.filesDir, "backup-cache").listFiles()!!
		assertTrue(entries.any { it.isFile && it.name != "index" })
		assertTrue(entries.all { !it.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-backup-cache") })
	}

	@Test
	fun verifySeededPayloads() {
		assertEquals(nonce, context.getSharedPreferences("appearance", 0).getString("backup_nonce", null))
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		var verified = false
		val result = store.restore { material, _ ->
			assertArrayEquals("synthetic-backup-token-$nonce".toByteArray(), material)
			verified = true
			SessionVerification.Verified(AccountId("synthetic-backup-account"), true)
		}
		assertTrue(verified)
		assertTrue(result is RestoreResult.Restored)
		val session = (result as RestoreResult.Restored).session
		assertArrayEquals("synthetic-backup-token-$nonce".toByteArray(), store.read(session))
		assertArrayEquals("synthetic-backup-cache-$nonce".toByteArray(), EncryptedCache(context, coordinator).read(session))
	}

	@Test
	fun verifyRestoredAppearanceAndAbsentSession() {
		val appearance = context.getSharedPreferences("appearance", 0)
		assertEquals("black", appearance.getString("theme", null))
		assertEquals("kk", appearance.getString("language", null))
		assertEquals(nonce, appearance.getString("backup_nonce", null))
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		assertFalse(File(context.filesDir, "backup-cache").exists())
		val coordinator = SessionCoordinator(context)
		val store = SessionStore(context, coordinator)
		assertEquals(RestoreResult.Absent, store.restore { _, _ -> error("Absent session must not be verified") })
		coordinator.logout()
		assertFalse(store.restore { _, _ -> error("Logged-out session must not be verified") } is RestoreResult.Restored)
	}
}
