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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException

internal fun interface IdentityVerifier {
	fun verifiedAccount(session: UnverifiedSession): AccountId?
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
) {
	private var session: ActiveSession? = null
	private var remote: UnverifiedSession? = null
	@Volatile private var loginRevision: Long? = null

	fun login(username: String, password: CharArray): GatewayResult<Unit> {
		val revision = coordinator.beginLogin()
		loginRevision = revision
		val result = try { gateway.login(username, password) } catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.UNAVAILABLE)
		} finally { password.fill('\u0000') }
		if (result is GatewayResult.Failure) return result
		val candidate = (result as GatewayResult.Success).value
		val account = try { verifier.verifiedAccount(candidate) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
		if (candidate.role != "student" || account == null) return GatewayResult.Failure(AppError.UNAVAILABLE)
		return try {
			val active = coordinator.activateIfUnchanged(revision, account) ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			coordinator.ifActive(active) {
				session = active
				remote = candidate
			} ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			GatewayResult.Success(Unit)
		} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
	}

	fun refresh(context: DiaryContext) {
		val active = session ?: return
		val authenticated = coordinator.ifActive(active) {
			emit(DiaryState.Loading)
			remote
		} ?: return
		val result = try { gateway.diary(authenticated, context) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
		coordinator.ifActive(active) {
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
							check(cache.write(active, encode(snapshot)))
							emit(DiaryState.Ready(snapshot))
						}
					}
					is GatewayResult.Failure -> emitFailure(active, context, result.error)
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) { emit(DiaryState.Error(AppError.CACHE_UNREADABLE)) }
		}
	}

	private fun emitFailure(active: ActiveSession, context: DiaryContext, error: AppError) {
		val saved = cache.read(active)?.let { decode(it) }?.takeIf { it.diary.date == context.date }
		emit(if (saved == null) DiaryState.Error(error) else DiaryState.Ready(saved, error))
	}

	fun logout() {
		loginRevision?.let { coordinator.cancelLogin(it) }
		loginRevision = null
		val active = session ?: return
		coordinator.ifActive(active) {
			remote = null
			session = null
			try { coordinator.revokeIfActive(active) } finally { emit(DiaryState.Cleared) }
		}
	}
}

private fun encode(snapshot: DiarySnapshot): ByteArray {
	val bytes = ByteArrayOutputStream()
	DataOutputStream(bytes).use { out ->
		out.writeInt(1)
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

private fun decode(bytes: ByteArray): DiarySnapshot {
	val input = DataInputStream(ByteArrayInputStream(bytes))
	check(input.readInt() == 1)
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
