package org.materialclass.session

import android.content.Context
import org.materialclass.cache.CacheFiles
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import java.io.File

class SessionStore(context: Context, keys: KeystoreKeys = KeystoreKeys()) {
	private val file = EncryptedFile(File(context.noBackupFilesDir, "session"), "session", keys, CacheFiles(context))

	@Synchronized
	fun read(): ByteArray? = file.read()

	@Synchronized
	fun write(value: ByteArray) = file.write(value)

	@Synchronized
	fun reset() = file.reset()
}
