package org.materialclass.session

import android.os.Process
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.cache.CacheFiles
import org.materialclass.cache.EncryptedCache
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanupProcessRestartTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context = instrumentation.targetContext
	private val args = InstrumentationRegistry.getArguments()
	private val old = CacheNamespace("a".repeat(64))
	private val fresh = CacheNamespace("b".repeat(64))
	private val keys = KeystoreKeys()
	private val files = CacheFiles(context)
	private val sessionFile = File(context.noBackupFilesDir, "session")
	private val material = "synthetic-session-material".toByteArray()
	private class InjectedFailure(val stage: String) : RuntimeException(stage)

	private fun id(namespace: CacheNamespace, suffix: String): String = keys.namespace(namespace.value + suffix, true)
	private fun cache(namespace: CacheNamespace, suffix: String): EncryptedFile {
		val id = id(namespace, suffix)
		return EncryptedFile(File(files.directory, id), "cache-$id", keys, files)
	}

	@Test
	fun preparePendingCleanupAndWaitForKill() {
		assertEquals("prepare", args.getString("cleanupRestartPhase"))
		val stage = requireNotNull(args.getString("cleanupRestartStage"))
		check(stage in listOf("write", "cache", "session", "clear"))
		assertNull(FileCleanupJournal(context).read())
		val coordinator = SessionCoordinator(context)
		val session = requireNotNull(coordinator.acceptRestore(AccountId("synthetic-account"), old))
		val store = SessionStore(context, coordinator)
		assertTrue(store.write(session, material))
		assertTrue(sessionFile.isFile)
		assertTrue(keys.contains("session"))
		assertArrayEquals(material, store.read(session))
		assertFalse(sessionFile.readBytes().toString(Charsets.UTF_8).contains(material.toString(Charsets.UTF_8)))
		for (namespace in listOf(old, fresh)) for (suffix in listOf("", ":schedule")) {
			cache(namespace, suffix).write("synthetic-$suffix".toByteArray())
		}
		val real = FileCleanupJournal(context)
		val injected = InjectedFailure(stage)
		val journal = object : CleanupJournal {
			override fun read() = real.read()
			override fun write(namespace: CacheNamespace) {
				real.write(namespace)
				if (stage == "write") throw injected
			}
			override fun clear() {
				if (stage == "clear") throw injected
				real.clear()
			}
		}
		try {
			CleanupRecovery(journal, {
				if (stage == "cache") throw injected
				EncryptedCache.clearNamespace(context, it)
			}, {
				if (stage == "session") throw injected
				SessionStore.clear(context)
			}).cleanup(old)
			fail("Expected $stage failure")
		} catch (error: InjectedFailure) {
			assertSame(injected, error)
			assertNotEquals("write", stage)
		} catch (error: LogoutStorageException) {
			assertEquals("write", stage)
			assertSame(injected, error.cause)
		}
		assertEquals(old.value, real.read()?.value)
		val sessionPending = stage == "write" || stage == "session"
		assertEquals(sessionPending, sessionFile.isFile)
		assertEquals(sessionPending, keys.contains("session"))
		for (name in listOf("cleanup-journal", "cleanup-recovery")) {
			assertFalse(File(context.noBackupFilesDir, name).readBytes().toString(Charsets.UTF_8).contains(old.value))
		}
		File(context.noBackupFilesDir, "restart-test-pid").writeText(Process.myPid().toString())
		Log.i("CleanupRestartTest", "READY:$stage:${Process.myPid()}")
		Thread.sleep(120000)
		fail("The preparing process was not terminated")
	}

	@Test
	fun recoverBeforeLoginAfterProcessDeath() {
		assertEquals("verify", args.getString("cleanupRestartPhase"))
		val stage = requireNotNull(args.getString("cleanupRestartStage"))
		check(stage in listOf("write", "cache", "session", "clear"))
		val previousPid = File(context.noBackupFilesDir, "restart-test-pid").readText().toInt()
		assertNotEquals(previousPid, Process.myPid())
		assertEquals(old.value, FileCleanupJournal(context).read()?.value)
		val sessionPending = stage == "write" || stage == "session"
		assertEquals(sessionPending, sessionFile.isFile)
		assertEquals(sessionPending, keys.contains("session"))
		if (sessionPending) {
			val encrypted = EncryptedFile(sessionFile, "session", keys, files)
			assertTrue(requireNotNull(encrypted.read()).toString(Charsets.UTF_8).contains(material.toString(Charsets.UTF_8)))
		}
		SessionCoordinator(context).beginLogin()
		assertNull(FileCleanupJournal(context).read())
		assertFalse(sessionFile.exists())
		assertFalse(keys.contains("session"))
		for (suffix in listOf("", ":schedule")) {
			val oldId = id(old, suffix)
			assertFalse(File(files.directory, oldId).exists())
			assertFalse(keys.contains("cache-$oldId"))
			val freshId = id(fresh, suffix)
			assertTrue(keys.contains("cache-$freshId"))
			assertEquals("synthetic-$suffix", cache(fresh, suffix).read()?.toString(Charsets.UTF_8))
		}
		instrumentation.sendStatus(0, Bundle().apply { putString("cleanupRestartVerified", stage) })
	}
}
