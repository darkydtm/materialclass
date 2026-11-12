package org.materialclass.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.materialclass.data.AccountId

/** Identify a key without exposing key material. */
data class KeyHandle(val alias: String)

interface KeyProvider {
	fun tokenKey(): KeyHandle
	fun cacheKey(accountId: AccountId): KeyHandle
	fun material(handle: KeyHandle): SecretKey
	fun deleteAll()
}

class AndroidKeystoreKeys : KeyProvider {
	private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

	override fun tokenKey(): KeyHandle = handle(TOKEN_ALIAS)

	override fun cacheKey(accountId: AccountId): KeyHandle = handle(CACHE_PREFIX + digest(accountId.value))

	override fun material(handle: KeyHandle): SecretKey {
		val existing = keyStore.getKey(handle.alias, null) as? SecretKey
		return existing ?: create(handle.alias)
	}

	override fun deleteAll() {
		val aliases = keyStore.aliases()
		while (aliases.hasMoreElements()) {
			val alias = aliases.nextElement()
			if (alias == TOKEN_ALIAS || alias.startsWith(CACHE_PREFIX)) keyStore.deleteEntry(alias)
		}
	}

	private fun handle(alias: String): KeyHandle {
		if (!keyStore.containsAlias(alias)) create(alias)
		return KeyHandle(alias)
	}

	private fun create(alias: String): SecretKey {
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return generator.generateKey()
	}

	private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }

	private companion object {
		const val ANDROID_KEYSTORE = "AndroidKeyStore"
		const val TOKEN_ALIAS = "org.materialclass.session.token"
		const val CACHE_PREFIX = "org.materialclass.cache."
	}
}

interface SecureCipher {
	fun encrypt(key: KeyHandle, plaintext: ByteArray): ByteArray
	fun decrypt(key: KeyHandle, ciphertext: ByteArray): ByteArray
}

class KeystoreCipher(private val keys: KeyProvider) : SecureCipher {
	override fun encrypt(key: KeyHandle, plaintext: ByteArray): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, keys.material(key))
		return cipher.iv + cipher.doFinal(plaintext)
	}

	override fun decrypt(key: KeyHandle, ciphertext: ByteArray): ByteArray {
		require(ciphertext.size > IV_LENGTH) { "Ciphertext is too short" }
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(
			Cipher.DECRYPT_MODE,
			keys.material(key),
			GCMParameterSpec(TAG_BITS, ciphertext.copyOf(IV_LENGTH)),
		)
		return cipher.doFinal(ciphertext.copyOfRange(IV_LENGTH, ciphertext.size))
	}

	private companion object {
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_LENGTH = 12
		const val TAG_BITS = 128
	}
}
