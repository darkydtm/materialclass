package org.materialclass.cache

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.materialclass.data.AccountId

class CacheFiles(private val root: File) {
	init { root.mkdirs() }

	fun tokenFile(): File = File(root, TOKEN_FILE)

	fun cacheFile(accountId: AccountId): File = File(root, "cache-${digest(accountId.value)}.bin")

	fun cacheFiles(): List<File> = root.listFiles { file -> file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(".bin") }
		?.toList()
		.orEmpty()

	fun temporaryFiles(): List<File> = root.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.toList().orEmpty()

	fun clear() {
		tokenFile().delete()
		cacheFiles().forEach(File::delete)
		temporaryFiles().forEach(File::delete)
	}

	fun atomicWrite(target: File, bytes: ByteArray) {
		val temporary = File(target.parentFile, target.name + TEMP_SUFFIX)
		try {
			FileOutputStream(temporary).use { stream ->
				stream.write(bytes)
				stream.fd.sync()
			}
			if (!temporary.renameTo(target)) throw IllegalStateException("Atomic rename failed")
		} finally {
			if (temporary.exists()) temporary.delete()
		}
	}

	private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }

	private companion object {
		const val TOKEN_FILE = "session-token.bin"
		const val CACHE_PREFIX = "cache-"
		const val TEMP_SUFFIX = ".tmp"
	}
}
