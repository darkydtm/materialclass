package org.materialclass.data

import org.materialclass.api.AppError
import org.materialclass.api.Diary
import org.materialclass.api.DiaryContext
import org.materialclass.api.DiaryDay
import org.materialclass.api.DiarySubject
import org.materialclass.api.GatewayResult
import org.materialclass.api.SchoolGateway
import org.materialclass.api.UnverifiedSession
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.AccountId
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionVerification
import org.materialclass.session.RestoreResult
import org.materialclass.security.StorageLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException

internal fun interface IdentityVerifier {
	fun verify(session: UnverifiedSession): SessionVerification
}

internal interface DiaryCache {
	fun read(session: ActiveSession): ByteArray?
	fun write(session: ActiveSession, bytes: ByteArray): Boolean
}

internal class EncryptedDiaryCache(private val cache: EncryptedCache) : DiaryCache {
	override fun read(session: ActiveSession): ByteArray? = cache.read(session)
	override fun write(session: ActiveSession, bytes: ByteArray): Boolean = cache.write(session, bytes)
}

internal data class DiarySnapshot(val diary: Diary, val lastSuccessMillis: Long)

internal sealed class DiaryState {
	data object Loading : DiaryState()
	data object Cleared : DiaryState()
	data class Ready(val snapshot: DiarySnapshot, val staleError: AppError? = null) : DiaryState()
	data class Error(val error: AppError) : DiaryState()
}

internal class SchoolRepository(
	private val gateway: SchoolGateway,
	private val verifier: IdentityVerifier,
	private val coordinator: SessionCoordinator,
	private val cache: DiaryCache,
	private val clock: () -> Long,
	private val emit: (DiaryState) -> Unit,
	private val store: SessionStore,
) {
	@Volatile private var session: ActiveSession? = null
	private var remote: UnverifiedSession? = null
	private var refreshRevision = 0L
	@Volatile private var loginRevision: Long? = null

	fun login(username: String, password: CharArray): GatewayResult<Unit> {
		val revision = synchronized(StorageLock) {
			coordinator.beginLogin().also { loginRevision = it }
		}
		try {
			val result = try { gateway.login(username, password) } catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				GatewayResult.Failure(AppError.UNAVAILABLE)
			} finally { password.fill('\u0000') }
			if (result is GatewayResult.Failure) return result
			val candidate = (result as GatewayResult.Success).value
			val verification = try { verifier.verify(candidate) } catch (error: CancellationException) { throw error } catch (_: Exception) { SessionVerification.Retry }
			if (candidate.role != "student" || verification !is SessionVerification.Verified || !verification.pupil) return GatewayResult.Failure(AppError.UNAVAILABLE)
			return try {
				synchronized(StorageLock) {
					val active = coordinator.activateIfUnchanged(revision, verification.account) {
						session = null
						remote = null
						emit(DiaryState.Cleared)
					} ?: return@synchronized GatewayResult.Failure(AppError.UNAVAILABLE)
					val material = candidate.token.toByteArray(Charsets.UTF_8)
					try {
						check(store.write(active, material))
					} catch (error: Exception) {
						coordinator.revokeIfActive(active)
						throw error
					} finally { material.fill(0) }
					session = active
					remote = candidate
					GatewayResult.Success(Unit)
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
		} finally {
			synchronized(StorageLock) {
				if (loginRevision == revision) loginRevision = null
			}
		}
	}

	fun restore(): RestoreResult {
		var candidate: UnverifiedSession? = null
		val result = store.restore { material ->
			val remote = UnverifiedSession(material.toString(Charsets.UTF_8), null, "student")
			val verification = verifier.verify(remote)
			if (verification is SessionVerification.Verified && verification.pupil) candidate = remote
			verification
		}
		if (result is RestoreResult.Restored) {
			coordinator.ifActive(result.session) {
				session = result.session
				remote = candidate
				emit(DiaryState.Cleared)
			} ?: return RestoreResult.Cancelled
		}
		return result
	}

	fun refresh(context: DiaryContext) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val authenticated = remote ?: return@ifActive null
			val revision = ++refreshRevision
			emit(DiaryState.Loading)
			authenticated to revision
		} ?: return
		val result = try { gateway.diary(request.first, context) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
		coordinator.ifActive(active) {
			if (request.second != refreshRevision) return@ifActive
			if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
				remote = null
				session = null
				coordinator.revokeIfActive(active)
				emit(DiaryState.Error(AppError.SESSION_EXPIRED))
				return@ifActive
			}
			try {
				when (result) {
					is GatewayResult.Success -> {
						if (result.value.date != context.date) {
							emitFailure(active, context, AppError.INCOMPATIBLE_RESPONSE)
						} else {
							val snapshot = DiarySnapshot(result.value, clock())
							check(cache.write(active, encode(context, snapshot)))
							emit(DiaryState.Ready(snapshot))
						}
					}
					is GatewayResult.Failure -> emitFailure(active, context, result.error)
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) { emit(DiaryState.Error(AppError.CACHE_UNREADABLE)) }
		}
	}

	private fun emitFailure(active: ActiveSession, context: DiaryContext, error: AppError) {
		val saved = cache.read(active)?.let { decode(it, context) }?.takeIf { it.diary.date == context.date }
		emit(if (saved == null) DiaryState.Error(error) else DiaryState.Ready(saved, error))
	}

	fun logout() = synchronized(StorageLock) {
		val pending = loginRevision
		loginRevision = null
		val active = session
		if (active == null) {
			if (!coordinator.logoutIfNoActive()) pending?.let { coordinator.cancelLogin(it) }
		} else {
			val revoked = coordinator.ifActive(active) {
				remote = null
				session = null
				try { coordinator.revokeIfActive(active) } finally { emit(DiaryState.Cleared) }
			}
			if (revoked != true) coordinator.logoutIfNoActive()
			pending?.let { coordinator.cancelLogin(it) }
		}
	}
}

private fun encode(context: DiaryContext, snapshot: DiarySnapshot): ByteArray {
	val bytes = ByteArrayOutputStream()
	DataOutputStream(bytes).use { out ->
		out.writeInt(2)
		out.writeLong(context.schoolId)
		out.writeInt(context.eduYear)
		out.writeLong(context.groupId)
		out.writeLong(snapshot.lastSuccessMillis)
		out.writeUTF(snapshot.diary.date)
		out.writeInt(snapshot.diary.days.size)
		snapshot.diary.days.forEach { day ->
			out.writeUTF(day.day); out.writeUTF(day.date); out.writeInt(day.subjects.size)
			day.subjects.forEach { subject ->
				out.writeUTF(subject.label); out.writeUTF(subject.type); out.writeUTF(subject.scheduleUuid)
				out.writeUTF(subject.homeworkType)
				listOf(subject.homeworkBody, subject.homeworkUuid).forEach { value ->
					out.writeBoolean(value != null)
					if (value != null) out.writeUTF(value)
				}
				out.writeBoolean(subject.hasBooks); out.writeBoolean(subject.hasFiles)
			}
		}
	}
	return bytes.toByteArray()
}

private fun decode(bytes: ByteArray, context: DiaryContext): DiarySnapshot? {
	val input = DataInputStream(ByteArrayInputStream(bytes))
	check(input.readInt() == 2)
	val schoolId = input.readLong()
	val eduYear = input.readInt()
	val groupId = input.readLong()
	if (schoolId != context.schoolId || eduYear != context.eduYear || groupId != context.groupId) return null
	val time = input.readLong()
	val date = input.readUTF()
	fun count(): Int = input.readInt().also { require(it in 0..10000) }
	fun nullable(): String? = if (input.readBoolean()) input.readUTF() else null
	val days = List(count()) {
		val day = input.readUTF(); val dayDate = input.readUTF()
		DiaryDay(day, dayDate, List(count()) {
			DiarySubject(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), nullable(), nullable(), input.readBoolean(), input.readBoolean())
		})
	}
	check(input.available() == 0)
	return DiarySnapshot(Diary(date, days), time)
}
