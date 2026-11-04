package org.materialclass.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageUnreadableException
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileCleanupJournalTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val path = File(context.noBackupFilesDir, "cleanup-journal")
	private val namespace = CacheNamespace("a".repeat(64))
	private val backupTemporary = File(context.noBackupFilesDir, "recovery-tmp")
	private val primaryTemporary = File(context.noBackupFilesDir, "cleanup-tmp")

	@Before
	fun setup() {
		check(!path.exists())
	}

	@After
	fun cleanup() {
		backupTemporary.delete()
		primaryTemporary.delete()
		path.delete()
		File(context.noBackupFilesDir, "cleanup-recovery").delete()
		KeystoreKeys().delete("cleanup-journal")
		KeystoreKeys().delete("cleanup-recovery")
	}

	@Test
	fun backupFailureStillPersistsPrimaryBeforeInvalidation() {
		check(!backupTemporary.exists() || backupTemporary.delete())
		check(backupTemporary.createNewFile())
		var invalidated = false
		try {
			try {
				CleanupRecovery(FileCleanupJournal(context), {}, {}, { invalidated = true }).cleanup(namespace)
				fail("Expected write failure")
			} catch (_: LogoutStorageException) { }
			assertTrue(invalidated)
			assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
		} finally { backupTemporary.delete() }
	}

	@Test
	fun failedCopiesAndCacheCleanupPreserveSessionKey() {
		check(!backupTemporary.exists() || backupTemporary.delete())
		check(!primaryTemporary.exists() || primaryTemporary.delete())
		check(backupTemporary.createNewFile())
		check(primaryTemporary.createNewFile())
		val keys = KeystoreKeys()
		keys.key("session", true)
		try {
			try {
				CleanupRecovery(FileCleanupJournal(context), { throw IllegalStateException("cache") }, {}, { keys.delete("session") }).cleanup(namespace)
				fail("Expected write failure")
			} catch (_: LogoutStorageException) { }
			assertTrue(KeystoreKeys().contains("session"))
		} finally { backupTemporary.delete(); primaryTemporary.delete(); keys.delete("session") }
	}

	@Test
	fun encryptedJournalSurvivesRecreationAndRepairsCorruption() {
		FileCleanupJournal(context).write(namespace)
		val bytes = path.readBytes()
		assertFalse(bytes.toString(Charsets.UTF_8).contains(namespace.value))
		assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		path.writeBytes(bytes)
		val recreated = FileCleanupJournal(context)
		assertEquals(namespace.value, recreated.read()?.value)
		recreated.write(namespace)
		File(context.noBackupFilesDir, "cleanup-recovery").delete()
		assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
	}

	@Test
	fun restartRetriesFailureAtEachCleanupStage() {
		for (stage in listOf("write", "cache", "session", "clear")) {
			val real = FileCleanupJournal(context)
			var fail = true
			var sessionClears = 0
			val journal = object : CleanupJournal {
				override fun read() = real.read()
				override fun write(namespace: CacheNamespace) {
					real.write(namespace)
					if (fail && stage == "write") throw IllegalStateException(stage)
				}
				override fun clear() {
					if (fail && stage == "clear") throw IllegalStateException(stage)
					real.clear()
				}
			}
			val recovery = CleanupRecovery(journal, { if (fail && stage == "cache") throw IllegalStateException(stage) }, {
				sessionClears++
				if (fail && stage == "session") throw IllegalStateException(stage)
			})
			try { recovery.cleanup(namespace); fail("Expected $stage failure") } catch (_: Exception) { }
			assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
			if (stage == "write") assertEquals(0, sessionClears)
			fail = false
			CleanupRecovery(FileCleanupJournal(context), {}, {}).recover()
			assertNull(FileCleanupJournal(context).read())
		}
	}

	@Test
	fun encryptedRecoveryRepairsCorruptionAndHidesNamespace() {
		FileCleanupJournal(context).write(namespace)
		val backup = File(context.noBackupFilesDir, "cleanup-recovery")
		val bytes = backup.readBytes()
		assertFalse(bytes.toString(Charsets.UTF_8).contains(namespace.value))
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		backup.writeBytes(bytes)
		val recreated = FileCleanupJournal(context)
		assertEquals(namespace.value, recreated.read()?.value)
		recreated.write(namespace)
		path.delete()
		assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
	}

	@Test
	fun recoveryRepairsCopyBeforeScopedCleanup() {
		FileCleanupJournal(context).write(namespace)
		val bytes = path.readBytes()
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		path.writeBytes(bytes)
		var cleared = false
		CleanupRecovery(FileCleanupJournal(context), {
			val backup = File(context.noBackupFilesDir, "cleanup-recovery")
			check(backup.delete())
			assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
			assertEquals(namespace.value, it.value)
			cleared = true
		}, {}).recover()
		assertTrue(cleared)
	}

	@Test
	fun failedRepairDoesNotStartCleanup() {
		FileCleanupJournal(context).write(namespace)
		val bytes = path.readBytes()
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		path.writeBytes(bytes)
		check(!primaryTemporary.exists() || primaryTemporary.delete())
		check(primaryTemporary.createNewFile())
		var cleared = false
		try {
			try { CleanupRecovery(FileCleanupJournal(context), { cleared = true }, { cleared = true }).recover(); fail("Repair must fail") } catch (_: java.io.IOException) { }
			assertFalse(cleared)
			assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
		} finally { primaryTemporary.delete() }
	}

	@Test
	fun secondReadFailurePreservesSessionKeyAndSkipsCleanup() {
		val keys = KeystoreKeys()
		keys.key("session", true)
		val backup = File(context.noBackupFilesDir, "cleanup-recovery")
		val real = FileCleanupJournal(context)
		var cleared = false
		val journal = object : CleanupJournal {
			override fun read() = real.read()
			override fun write(namespace: CacheNamespace) {
				check(backup.mkdir())
				real.write(namespace)
			}
			override fun clear() { throw AssertionError("Unexpected clear") }
		}
		try {
			try { CleanupRecovery(journal, { cleared = true }, { cleared = true }, { keys.delete("session") }).cleanup(namespace); fail("Read failure must fail closed") } catch (_: LogoutStorageException) { }
			assertFalse(cleared)
			assertTrue(KeystoreKeys().contains("session"))
		} finally { backup.delete(); keys.delete("session") }
	}

	@Test
	fun bothCorruptCopiesFailClosed() {
		FileCleanupJournal(context).write(namespace)
		for (copy in listOf(path, File(context.noBackupFilesDir, "cleanup-recovery"))) {
			val bytes = copy.readBytes()
			bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
			copy.writeBytes(bytes)
		}
		try { FileCleanupJournal(context).read(); fail("Both corrupt copies must fail closed") } catch (_: StorageUnreadableException) { }
	}

	@Test
	fun conflictingAuthenticatedCopiesFailClosed() {
		FileCleanupJournal(context).write(namespace)
		val backup = File(context.noBackupFilesDir, "cleanup-recovery")
		org.materialclass.security.EncryptedFile(backup, "cleanup-recovery", KeystoreKeys(), org.materialclass.cache.CacheFiles(context, "recovery-tmp")).write("b".repeat(64).toByteArray())
		try { FileCleanupJournal(context).read(); fail("Conflicting copies must fail closed") } catch (_: StorageUnreadableException) { }
	}

	@Test
	fun missingKeystoreKeyDoesNotEraseJournal() {
		FileCleanupJournal(context).write(namespace)
		KeystoreKeys().delete("cleanup-journal")
		try { FileCleanupJournal(context).read(); fail("Missing key must fail closed") } catch (_: StorageUnreadableException) { }
		assertTrue(path.exists())
	}
}
