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

	@Before
	fun setup() {
		check(!path.exists())
	}

	@After
	fun cleanup() {
		path.delete()
		File(context.noBackupFilesDir, "cleanup-recovery").delete()
		KeystoreKeys().delete("cleanup-journal")
		KeystoreKeys().delete("cleanup-recovery")
	}

	@Test
	fun encryptedJournalSurvivesRecreationAndRejectsCorruption() {
		FileCleanupJournal(context).write(namespace)
		val bytes = path.readBytes()
		assertFalse(bytes.toString(Charsets.UTF_8).contains(namespace.value))
		assertEquals(namespace.value, FileCleanupJournal(context).read()?.value)
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		path.writeBytes(bytes)
		try { FileCleanupJournal(context).read(); fail("Corruption must fail closed") } catch (_: StorageUnreadableException) { }
		assertTrue(path.exists())
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
	fun encryptedRecoveryRejectsCorruptionAndHidesNamespace() {
		FileCleanupJournal(context).write(namespace)
		val backup = File(context.noBackupFilesDir, "cleanup-recovery")
		val bytes = backup.readBytes()
		assertFalse(bytes.toString(Charsets.UTF_8).contains(namespace.value))
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
		backup.writeBytes(bytes)
		try { FileCleanupJournal(context).read(); fail("Corruption must fail closed") } catch (_: StorageUnreadableException) { }
		assertTrue(backup.exists())
	}

	@Test
	fun missingKeystoreKeyDoesNotEraseJournal() {
		FileCleanupJournal(context).write(namespace)
		KeystoreKeys().delete("cleanup-journal")
		try { FileCleanupJournal(context).read(); fail("Missing key must fail closed") } catch (_: StorageUnreadableException) { }
		assertTrue(path.exists())
	}
}
