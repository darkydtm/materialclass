package org.materialclass.security

import org.materialclass.cache.CacheFiles
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class EncryptedFile(
	private val file: File,
	private val alias: String,
	private val keys: KeystoreKeys,
	private val files: CacheFiles
) {
	fun read(): ByteArray? {
		if (!file.exists()) return null
		try {
			val bytes = file.readBytes()
			if (bytes.size < 29 || bytes[0] != 1.toByte()) throw StorageUnreadableException()
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, keys.key(alias, false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
			cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
			return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun write(value: ByteArray) {
		if (file.exists()) read()
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, keys.key(alias, !file.exists()))
		cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
		files.replace(file, byteArrayOf(1) + cipher.iv + cipher.doFinal(value))
	}

	fun reset() {
		if (file.exists() && !file.delete()) throw java.io.IOException("Could not delete encrypted file")
		keys.delete(alias)
	}
}
