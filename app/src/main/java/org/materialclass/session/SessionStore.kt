package org.materialclass.session

import android.content.Context
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import org.materialclass.cache.CacheFiles
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageUnreadableException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class LogoutStorageException(cause: Throwable) : Exception("Logout could not durably revoke the session", cause)

class SessionStore(context: Context, private val coordinator: SessionCoordinator) {
	private val file by lazy { EncryptedFile(File(context.noBackupFilesDir, "session"), "session", KeystoreKeys(), CacheFiles(context)) }
	private val marker = File(context.noBackupFilesDir, "session-revoked")

	companion object {
		private fun syncDirectory(directory: File) {
			val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
			try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
		}

		internal fun clear(context: Context) {
			val marker = AtomicFile(File(context.noBackupFilesDir, "session-revoked"))
			var failure: Exception? = null
			var revoked = false
			try {
				val output = marker.startWrite()
				try {
					output.write(1)
					output.fd.sync()
					marker.finishWrite(output)
				} catch (error: Exception) {
					marker.failWrite(output)
					throw error
				}
				syncDirectory(context.noBackupFilesDir)
				revoked = true
			} catch (error: Exception) {
				failure = error
			}
			var fileRemoved = false
			var keyRemoved = false
			try {
				val session = File(context.noBackupFilesDir, "session")
				if (session.exists() && !session.delete()) throw java.io.IOException("Could not delete session")
				syncDirectory(context.noBackupFilesDir)
				fileRemoved = true
			} catch (error: Exception) {
				if (failure == null) failure = error else failure.addSuppressed(error)
			}
			try {
				KeystoreKeys().delete("session")
				keyRemoved = true
			} catch (error: Exception) {
				if (failure == null) failure = error else failure.addSuppressed(error)
			}
			failure?.let {
				if (!revoked && !fileRemoved && !keyRemoved) throw LogoutStorageException(it)
				throw it
			}
		}
	}

	private fun decode(bytes: ByteArray): Pair<AccountId, ByteArray> {
		try {
			val input = DataInputStream(ByteArrayInputStream(bytes))
			if (input.readInt() != 1) throw StorageUnreadableException()
			val account = AccountId(input.readUTF())
			val length = input.readInt()
			if (length < 0 || length != input.available()) throw StorageUnreadableException()
			val material = ByteArray(length)
			input.readFully(material)
			return account to material
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun restore(): ActiveSession? = coordinator.restore {
		if (marker.exists() || File(marker.path + ".bak").exists() || File(marker.path + ".new").exists()) null
		else file.read()?.let { decode(it).first }
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) {
		file.read()?.let {
			val (account, material) = decode(it)
			if (account != session.account) throw StorageUnreadableException()
			material
		}
	}

	fun write(session: ActiveSession, value: ByteArray): Boolean = coordinator.ifActive(session) {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use {
			it.writeInt(1)
			it.writeUTF(session.account.value)
			it.writeInt(value.size)
			it.write(value)
		}
		file.write(bytes.toByteArray())
		syncDirectory(marker.parentFile!!)
		val atomicMarker = AtomicFile(marker)
		atomicMarker.delete()
		if (marker.exists() || File(marker.path + ".bak").exists() || File(marker.path + ".new").exists()) {
			throw java.io.IOException("Could not clear session revocation")
		}
		syncDirectory(marker.parentFile!!)
		true
	} ?: false
}