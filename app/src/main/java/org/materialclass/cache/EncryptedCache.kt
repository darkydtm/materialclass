package org.materialclass.cache

import android.content.Context
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.opaqueId
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import java.io.File

class EncryptedCache(context: Context, private val coordinator: SessionCoordinator, private val keys: KeystoreKeys = KeystoreKeys()) {
	private val files = CacheFiles(context)

	private fun file(session: ActiveSession): EncryptedFile {
		val id = opaqueId(session.account.value)
		return EncryptedFile(File(files.directory, id), "cache-$id", keys, files)
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) { file(session).read() }
	fun write(session: ActiveSession, value: ByteArray): Boolean = coordinator.ifActive(session) { file(session).write(value); true } ?: false
	fun reset(session: ActiveSession): Boolean = coordinator.ifActive(session) { file(session).reset(); true } ?: false
}
