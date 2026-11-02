package org.materialclass.session

import android.content.Context
import android.util.AtomicFile
import org.materialclass.cache.CacheFiles
import org.materialclass.security.EncryptedFile
import org.materialclass.security.KeystoreKeys
import org.materialclass.security.StorageUnreadableException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class SessionStore(context: Context, private val coordinator: SessionCoordinator) {
	private val file by lazy { EncryptedFile(File(context.noBackupFilesDir, "session"), "session", KeystoreKeys(), CacheFiles(context)) }
	private val marker = File(context.noBackupFilesDir, "session-revoked")

	companion object {
		internal fun clear(context: Context) {
			val marker = AtomicFile(File(context.noBackupFilesDir, "session-revoked"))
			var failure: Exception? = null
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
			} catch (error: Exception) {
				failure = error
			}
			try {
				val session = File(context.noBackupFilesDir, "session")
				if (session.exists() && !session.delete()) throw java.io.IOException("Could not delete session")
			} catch (error: Exception) {
				if (failure == null) failure = error else failure.addSuppressed(error)
			}
			try {
				KeystoreKeys().delete("session")
			} catch (error: Exception) {
				if (failure == null) failure = error else failure.addSuppressed(error)
			}
			failure?.let { throw it }
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
		val atomicMarker = AtomicFile(marker)
		atomicMarker.delete()
		if (marker.exists() || File(marker.path + ".bak").exists() || File(marker.path + ".new").exists()) {
			throw java.io.IOException("Could not clear session revocation")
		}
		true
	} ?: false
}