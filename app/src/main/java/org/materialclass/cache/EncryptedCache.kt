package org.materialclass.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.materialclass.data.ActiveSession
import org.materialclass.security.KeyProvider
import org.materialclass.security.SecureCipher

sealed interface CacheRead {
	data class Hit(val payload: ByteArray) : CacheRead
	data object Missing : CacheRead
	data object Unavailable : CacheRead
}

class EncryptedCache(
	private val files: CacheFiles,
	private val keys: KeyProvider,
	private val cipher: SecureCipher,
	private val lock: Any,
	private val current: () -> ActiveSession?,
) {
	fun write(session: ActiveSession, payload: ByteArray): Boolean = synchronized(lock) {
		if (current() != session) return@synchronized false
		try {
			val encrypted = cipher.encrypt(keys.cacheKey(session.accountId), payload)
			val record = ByteArrayOutputStream().also { output ->
				DataOutputStream(output).use { data ->
					data.writeInt(MAGIC)
					data.writeUTF(session.accountId.value)
					data.writeLong(session.generation.value)
					data.writeInt(encrypted.size)
					data.write(encrypted)
				}
			}.toByteArray()
			files.atomicWrite(files.cacheFile(session.accountId), record)
			current() == session
		} catch (_: Exception) {
			false
		}
	}

	fun read(session: ActiveSession): CacheRead = synchronized(lock) {
		if (current() != session) return@synchronized CacheRead.Unavailable
		val file = files.cacheFile(session.accountId)
		if (!file.isFile) return@synchronized CacheRead.Missing
		try {
			val hit = DataInputStream(file.inputStream()).use { data ->
				if (data.readInt() != MAGIC) throw IllegalArgumentException("cache header")
				if (data.readUTF() != session.accountId.value || data.readLong() != session.generation.value) throw IllegalArgumentException("cache scope")
				val length = data.readInt()
				if (length < 0 || length > MAX_CACHE_BYTES) throw IllegalArgumentException("cache size")
				val encrypted = ByteArray(length)
				data.readFully(encrypted)
				CacheRead.Hit(cipher.decrypt(keys.cacheKey(session.accountId), encrypted))
			}
			if (current() == session) hit else CacheRead.Unavailable
		} catch (_: Exception) {
			file.delete()
			CacheRead.Unavailable
		}
	}

	private companion object {
		const val MAGIC = 0x4D434331
		const val MAX_CACHE_BYTES = 32 * 1024 * 1024
	}
}
