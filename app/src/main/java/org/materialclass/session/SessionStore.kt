package org.materialclass.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import org.materialclass.cache.CacheFiles
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.security.KeyProvider
import org.materialclass.security.SecureCipher

class SessionStore(
	private val files: CacheFiles,
	private val keys: KeyProvider,
	private val cipher: SecureCipher,
) {
	fun save(session: ActiveSession, token: String, expiresAt: Instant) {
		require(token.isNotEmpty()) { "Token must not be empty" }
		val plaintext = ByteArrayOutputStream().also { output ->
			DataOutputStream(output).use { data -> data.writeUTF(token) }
		}.toByteArray()
		val encrypted = cipher.encrypt(keys.tokenKey(), plaintext)
		val record = ByteArrayOutputStream().also { output ->
			DataOutputStream(output).use { data ->
				data.writeInt(MAGIC)
				data.writeUTF(session.accountId.value)
				data.writeLong(session.generation.value)
				data.writeUTF(expiresAt.toString())
				data.writeInt(encrypted.size)
				data.write(encrypted)
			}
		}.toByteArray()
		files.atomicWrite(files.tokenFile(), record)
	}

	fun load(): StoredSession? {
		val file = files.tokenFile()
		if (!file.isFile) return null
		return try {
			DataInputStream(file.inputStream()).use { data ->
				if (data.readInt() != MAGIC) return null
				val account = AccountId(data.readUTF())
				val generation = Generation(data.readLong())
				val expiresAt = Instant.parse(data.readUTF())
				val length = data.readInt()
				if (length <= 0 || length > MAX_RECORD_BYTES) return null
				val encrypted = ByteArray(length)
				data.readFully(encrypted)
				val token = DataInputStream(ByteArrayInputStream(cipher.decrypt(keys.tokenKey(), encrypted))).use { it.readUTF() }
				if (token.isEmpty()) return null
				StoredSession(ActiveSession(account, generation), token, expiresAt)
			}
		} catch (_: Exception) {
			null
		}
	}

	fun clear() { files.tokenFile().delete() }

	private companion object {
		const val MAGIC = 0x4D435331
		const val MAX_RECORD_BYTES = 1024 * 1024
	}
}

data class StoredSession(
	val session: ActiveSession,
	val token: String,
	val expiresAt: Instant,
)
