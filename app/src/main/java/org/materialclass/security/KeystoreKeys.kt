package org.materialclass.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class StorageUnreadableException(cause: Throwable? = null) : Exception("Encrypted storage is unreadable", cause)

internal class KeystoreKeys {
	private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

	fun key(alias: String, create: Boolean): SecretKey = synchronized(StorageLock) {
		try {
			val existing = store.getKey(alias, null)
			if (existing != null) return@synchronized existing as SecretKey
			if (!create) throw StorageUnreadableException()
			val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
			generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build())
			return@synchronized generator.generateKey()
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun delete(alias: String) = synchronized(StorageLock) { store.deleteEntry(alias) }

	fun namespace(value: String, create: Boolean): String = synchronized(StorageLock) {
		try {
			val alias = "cache-namespace"
			var key = store.getKey(alias, null) as SecretKey?
			if (key == null) {
				if (!create) throw StorageUnreadableException()
				val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
				generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
					.setDigests(KeyProperties.DIGEST_SHA256).build())
				key = generator.generateKey()
			}
			val mac = Mac.getInstance("HmacSHA256")
			mac.init(key)
			mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun deleteCacheKeys() = synchronized(StorageLock) {
		val aliases = store.aliases().toList().filter { it.startsWith("cache-") }
		var failure: Exception? = null
		aliases.forEach {
			try { store.deleteEntry(it) } catch (error: Exception) {
				if (failure == null) failure = error else failure?.addSuppressed(error)
			}
		}
		failure?.let { throw it }
	}
}
