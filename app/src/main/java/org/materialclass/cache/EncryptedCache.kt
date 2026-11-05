package org.materialclass.cache

import android.content.Context
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageLock
import java.io.IOException
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import java.io.File

class EncryptedCache(context: Context, private val coordinator: SessionCoordinator, private val kind: String = "diary") {
	private val files by lazy { CacheFiles(context) }
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
		val id = keys.namespace(session.cacheNamespace.value + if (kind == "diary") "" else ":$kind", false)
		return EncryptedFile(File(files.directory, id), "cache-$id", keys, files)
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) { file(session).read() }
	fun write(session: ActiveSession, value: ByteArray): Boolean = coordinator.ifActive(session) { file(session).write(value); true } ?: false
	fun reset(session: ActiveSession): Boolean = coordinator.ifActive(session) { file(session).reset(); true } ?: false

	companion object {
		internal fun clearNamespace(
			context: Context,
			namespace: org.materialclass.session.CacheNamespace,
			deleteFile: (File) -> Boolean = { it.delete() },
			deleteKey: (String) -> Unit = { KeystoreKeys().delete(it) },
		) = synchronized(StorageLock) {
			val files = CacheFiles(context)
			val keys = KeystoreKeys()
			if (!keys.contains("cache-namespace")) {
				if (files.directory.listFiles()?.isEmpty() != true) throw IOException("Cache namespace key is missing")
				return@synchronized
			}
			var failure: Exception? = null
			for (suffix in listOf("", ":schedule", ":grades")) {
				try {
					val id = keys.namespace(namespace.value + suffix, false)
					EncryptedFile(File(files.directory, id), "cache-$id", keys, files).reset(deleteFile, deleteKey)
				} catch (error: Exception) {
					if (failure == null) failure = error else failure.addSuppressed(error)
				}
			}
			try {
				val descriptor = android.system.Os.open(files.directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
				try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
			} catch (error: Exception) {
				if (failure == null) failure = error else failure.addSuppressed(error)
			}
			failure?.let { throw it }
		}
	}

	fun clearAll() = synchronized(StorageLock) {
		var failure: Exception? = null
		try {
			val entries = files.directory.listFiles() ?: throw IOException("Could not list cache")
			for (entry in entries) {
				try {
					if (!entry.isFile || !entry.delete()) throw IOException("Could not delete cache")
				} catch (error: Exception) {
					if (failure == null) failure = error else failure.addSuppressed(error)
				}
			}
		} catch (error: Exception) {
			failure = error
		}
		try {
			keys.deleteCacheKeys()
		} catch (error: Exception) {
			if (failure == null) failure = error else failure.addSuppressed(error)
		}
		failure?.let { throw it }
	}
}
