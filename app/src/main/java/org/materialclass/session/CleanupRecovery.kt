package org.materialclass.session

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.materialclass.cache.CacheFiles
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageUnreadableException
import java.io.File
import java.io.IOException

internal interface CleanupJournal {
	fun read(): CacheNamespace?
	fun write(namespace: CacheNamespace)
	fun clear()
}

private class CleanupBackupException(cause: Throwable) : IOException("Could not preserve cleanup recovery", cause)

internal class CleanupRecovery(private val journal: CleanupJournal, private val clearCache: (CacheNamespace) -> Unit, private val clearSession: () -> Unit, private val invalidateSession: () -> Unit = {}) {
	fun recover() {
		val namespace = journal.read() ?: return
		journal.write(namespace)
		perform(namespace)
	}

	fun cleanup(namespace: CacheNamespace?) {
		if (namespace == null) {
			recover()
			clearSession()
			return
		}
		try {
			val recorded = journal.read()
			if (recorded != null && recorded.value != namespace.value) throw IOException("Another cleanup is pending")
		} catch (error: Exception) {
			throw LogoutStorageException(error)
		}
		try {
			journal.write(namespace)
		} catch (error: Exception) {
			if (error is CleanupBackupException) {
				try { clearCache(namespace) } catch (failure: Exception) { error.addSuppressed(failure) }
			}
			try { invalidateSession() } catch (failure: Exception) { error.addSuppressed(failure) }
			throw LogoutStorageException(error)
		}
		perform(namespace)
	}

	private fun perform(namespace: CacheNamespace) {
		var failure: Exception? = null
		try { clearCache(namespace) } catch (error: Exception) { failure = error }
		try { clearSession() } catch (error: Exception) {
			if (failure == null) failure = error else failure.addSuppressed(error)
		}
		failure?.let { throw it }
		journal.clear()
	}
}

internal class FileCleanupJournal(context: Context) : CleanupJournal {
	private val directory = context.noBackupFilesDir
	private val path = File(directory, "cleanup-journal")
	private val file by lazy { EncryptedFile(path, "cleanup-journal", KeystoreKeys(), CacheFiles(context, "cleanup-tmp")) }
	private val backupPath = File(directory, "cleanup-recovery")
	private val backup by lazy { EncryptedFile(backupPath, "cleanup-recovery", KeystoreKeys(), CacheFiles(context, "recovery-tmp")) }

	private fun syncDirectory() {
		val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
		try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
	}

	private fun readFile(source: EncryptedFile): CacheNamespace? {
		val bytes = source.read() ?: return null
		try {
			return try { CacheNamespace(bytes.toString(Charsets.UTF_8)) } catch (_: IllegalArgumentException) { throw StorageUnreadableException() }
		} finally { bytes.fill(0) }
	}

	override fun read(): CacheNamespace? {
		val recorded = readFile(file)
		val saved = readFile(backup)
		if (recorded != null && saved != null && recorded.value != saved.value) throw StorageUnreadableException()
		return recorded ?: saved
	}

	override fun write(namespace: CacheNamespace) {
		val existing = read()
		if (existing != null && existing.value != namespace.value) throw IOException("Another cleanup is pending")
		val bytes = namespace.value.toByteArray(Charsets.UTF_8)
		try {
			try { backup.write(bytes); syncDirectory() } catch (error: Exception) { throw CleanupBackupException(error) }
			file.write(bytes)
			syncDirectory()
		} finally { bytes.fill(0) }
	}

	override fun clear() {
		if (path.exists() && !path.delete()) throw IOException("Could not delete cleanup journal")
		syncDirectory()
		if (backupPath.exists() && !backupPath.delete()) throw IOException("Could not delete cleanup recovery")
		syncDirectory()
	}
}
