package org.materialclass.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class StorageUnreadableException(cause: Throwable? = null) : Exception("Encrypted storage is unreadable", cause)

class KeystoreKeys {
	private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

	@Synchronized
	fun key(alias: String, create: Boolean): SecretKey {
		try {
			val existing = store.getKey(alias, null)
			if (existing != null) return existing as SecretKey
			if (!create) throw StorageUnreadableException()
			val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
			generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build())
			return generator.generateKey()
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	@Synchronized
	fun delete(alias: String) = store.deleteEntry(alias)
}

fun opaqueId(value: String): String = MessageDigest.getInstance("SHA-256")
	.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
