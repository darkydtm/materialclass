package org.materialclass.security

import org.materialclass.cache.CacheFiles
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedFile(
	private val file: File,
	private val alias: String,
	private val keys: KeystoreKeys,
	private val files: CacheFiles
) {
	fun read(): ByteArray? {
		if (!file.exists()) return null
		try {
			val bytes = file.readBytes()
			if (bytes.size < 29 || bytes[0] != 1.toByte()) throw StorageCorruptException()
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, keys.key(alias, false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
			cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
			return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
		} catch (error: javax.crypto.AEADBadTagException) {
			throw StorageCorruptException(error)
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun write(value: ByteArray, replaceCorrupt: Boolean = false) {
		if (file.exists()) {
			try { read() } catch (error: StorageCorruptException) { if (!replaceCorrupt) throw error }
		}
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, keys.key(alias, !file.exists()))
		cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
		files.replace(file, byteArrayOf(1) + cipher.iv + cipher.doFinal(value))
	}

	fun reset(deleteFile: (File) -> Boolean = { it.delete() }, deleteKey: (String) -> Unit = { keys.delete(it) }) {
		var failure: Exception? = null
		try {
			if (file.exists() && !deleteFile(file)) throw java.io.IOException("Could not delete encrypted file")
		} catch (error: Exception) {
			failure = error
		}
		try {
			deleteKey(alias)
		} catch (error: Exception) {
			if (failure == null) failure = error else failure.addSuppressed(error)
		}
		failure?.let { throw it }
	}
}
