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
import java.security.MessageDigest
import java.util.concurrent.CancellationException

fun interface SessionVerifier {
	fun verify(material: ByteArray): SessionVerification
}

sealed class SessionVerification {
	class Verified(val account: AccountId, val pupil: Boolean, val scheduleSession: org.materialclass.api.VerifiedScheduleSession? = null, val diarySession: org.materialclass.api.VerifiedDiarySession? = null) : SessionVerification()
	data object Expired : SessionVerification()
	data object Invalid : SessionVerification()
	data object Retry : SessionVerification()
}

sealed class RestoreResult {
	class Restored(val session: ActiveSession) : RestoreResult()
	data object Absent : RestoreResult()
	data object Retry : RestoreResult()
	data object Invalid : RestoreResult()
	data object Cancelled : RestoreResult()
}

class LogoutStorageException(cause: Throwable) : Exception("Logout could not durably revoke the session", cause)

class SessionStore(context: Context, private val coordinator: SessionCoordinator) {
	private val file by lazy { EncryptedFile(File(context.noBackupFilesDir, "session"), "session", KeystoreKeys(), CacheFiles(context)) }
	private val marker = File(context.noBackupFilesDir, "session-revoked")

	companion object {
		private val restoreLock = Any()

		private fun syncDirectory(directory: File) {
			val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
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

	private class StoredSession(val account: AccountId, val namespace: CacheNamespace, val material: ByteArray)

	private fun decode(bytes: ByteArray): StoredSession {
		try {
			val input = DataInputStream(ByteArrayInputStream(bytes))
			if (input.readInt() != 2) throw StorageUnreadableException()
			val account = AccountId(input.readUTF())
			val namespace = CacheNamespace(input.readUTF())
			val length = input.readInt()
			if (length < 0 || length != input.available()) throw StorageUnreadableException()
			val material = ByteArray(length)
			input.readFully(material)
			return StoredSession(account, namespace, material)
		} catch (error: StorageUnreadableException) {
			throw error
		} catch (error: Exception) {
			throw StorageUnreadableException(error)
		}
	}

	fun restore(): ActiveSession? = null

	fun restore(verifier: SessionVerifier): RestoreResult = restore(verifier) {}

	internal fun restore(verifier: SessionVerifier, onRestored: (ActiveSession) -> Unit): RestoreResult = synchronized(restoreLock) { restoreLeased({}, { material, _ -> verifier.verify(material) }, onRestored) }

	internal fun restoreLeased(beforeAdmission: () -> Unit, verifier: (ByteArray, RequestLease) -> SessionVerification, onRestored: (ActiveSession) -> Unit): RestoreResult {
		val revision = coordinator.beginRestore() ?: return RestoreResult.Cancelled
		val candidate = coordinator.finishRestore(revision) {
			if (marker.exists() || File(marker.path + ".bak").exists() || File(marker.path + ".new").exists()) null
			else file.read()?.let { bytes -> try { decode(bytes) } finally { bytes.fill(0) } }
		} ?: return RestoreResult.Absent
		val verification = try {
			beforeAdmission()
			val lease = coordinator.admit(revision) ?: return RestoreResult.Cancelled
			try { verifier(candidate.material, lease) } finally { coordinator.finish(lease) }
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			SessionVerification.Retry
		} finally {
			candidate.material.fill(0)
		}
		return coordinator.finishRestore(revision) {
			when (verification) {
				SessionVerification.Retry -> RestoreResult.Retry
				SessionVerification.Expired, SessionVerification.Invalid -> { coordinator.rejectRestore(); RestoreResult.Invalid }
				is SessionVerification.Verified -> {
					val matching = MessageDigest.isEqual(candidate.account.value.toByteArray(Charsets.UTF_8), verification.account.value.toByteArray(Charsets.UTF_8))
					if (!matching || !verification.pupil) {
						coordinator.rejectRestore()
						RestoreResult.Invalid
					} else {
						val active = coordinator.acceptRestore(verification.account, candidate.namespace)
						onRestored(active)
						RestoreResult.Restored(active)
					}
				}
			}
		} ?: RestoreResult.Cancelled
	}

	fun read(session: ActiveSession): ByteArray? = coordinator.ifActive(session) {
		file.read()?.let { bytes ->
			val stored = try { decode(bytes) } finally { bytes.fill(0) }
			if (stored.account != session.account || stored.namespace.value != session.cacheNamespace.value) {
				stored.material.fill(0)
				throw StorageUnreadableException()
			}
			stored.material
		}
	}

	fun write(session: ActiveSession, value: ByteArray): Boolean = coordinator.ifActive(session) {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use {
			it.writeInt(2)
			it.writeUTF(session.account.value)
			it.writeUTF(session.cacheNamespace.value)
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