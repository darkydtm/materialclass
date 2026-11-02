package org.materialclass.cache

import android.content.Context
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageLock
import java.io.IOException
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import java.io.File

class EncryptedCache(context: Context, private val coordinator: SessionCoordinator) {
	private val files = CacheFiles(context)
	private val keys = KeystoreKeys()

	private fun file(session: ActiveSession): EncryptedFile {
		val entries = files.directory.listFiles() ?: throw IOException("Could not list cache")
		val marker = EncryptedFile(File(files.directory, "index"), "cache-guard", keys, files)
		val guard = keys.namespace("materialclass-cache-index", entries.isEmpty()).toByteArray(Charsets.UTF_8)
		val previous = marker.read()
		if (previous == null) {
			if (entries.isNotEmpty()) throw org.materialclass.security.StorageUnreadableException()
			marker.write(guard)
		} else if (!java.security.MessageDigest.isEqual(guard, previous)) {
			throw org.materialclass.security.StorageUnreadableException()
		}
		val id = keys.namespace(session.account.value, false)
		return EncryptedFile(File(files.directory, id), "cache-$id", keys, files)
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) { file(session).read() }
	fun write(session: ActiveSession, value: ByteArray): Boolean = coordinator.ifActive(session) { file(session).write(value); true } ?: false
	fun reset(session: ActiveSession): Boolean = coordinator.ifActive(session) { file(session).reset(); true } ?: false

	fun clearAll() = synchronized(StorageLock) {
		val entries = files.directory.listFiles() ?: throw IOException("Could not list cache")
		for (entry in entries) {
			if (!entry.isFile || !entry.delete()) throw IOException("Could not delete cache")
		}
		keys.deleteCacheKeys()
	}
}
