package org.materialclass.session

import android.content.Context
import org.materialclass.cache.CacheFiles
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import java.io.File

class SessionStore(context: Context, private val coordinator: SessionCoordinator) {
	private val file = EncryptedFile(File(context.noBackupFilesDir, "session"), "session", KeystoreKeys(), CacheFiles(context))

	companion object {
		internal fun clear(context: Context) {
			EncryptedFile(File(context.noBackupFilesDir, "session"), "session", KeystoreKeys(), CacheFiles(context)).reset()
		}
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) { file.read() }

	fun write(session: ActiveSession, value: ByteArray): Boolean =
		coordinator.ifActive(session) { file.write(value); true } ?: false
}
