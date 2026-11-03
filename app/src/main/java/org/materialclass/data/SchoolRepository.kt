package org.materialclass.data

import org.materialclass.api.AppError
import org.materialclass.api.Diary
import org.materialclass.api.DiaryContext
import org.materialclass.api.DiaryDay
import org.materialclass.api.DiarySubject
import org.materialclass.api.GatewayResult
import org.materialclass.api.SchoolGateway
import org.materialclass.api.UnverifiedSession
import org.materialclass.api.VerifiedScheduleSession
import org.materialclass.api.VerifiedDiarySession
import org.materialclass.api.Schedule
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.AccountId
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionVerification
import org.materialclass.session.RestoreResult
import org.materialclass.session.RequestLease
import org.materialclass.security.StorageLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException

internal fun interface IdentityVerifier {
	fun verify(session: UnverifiedSession, lease: RequestLease): SessionVerification
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
	private var scheduleSession: VerifiedScheduleSession? = null
	private var diarySession: VerifiedDiarySession? = null
	private var refreshRevision = 0L
	private var scheduleRevision = 0L
	private var weekRevision = 0L
	@Volatile private var loginRevision: Long? = null
	internal var afterRestoreActivation: () -> Unit = {}
	internal var beforeRequestAdmission: () -> Unit = {}
	internal var beforeIdentityAdmission: () -> Unit = {}

	fun login(username: String, password: CharArray): GatewayResult<Unit> {
		val revision = synchronized(StorageLock) {
			coordinator.beginLogin().also { loginRevision = it }
		}
		try {
			beforeIdentityAdmission()
			val lease = coordinator.admit(revision) ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			val result = try { gateway.login(username, password, lease) } catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				GatewayResult.Failure(AppError.UNAVAILABLE)
			} finally { password.fill('\u0000'); coordinator.finish(lease) }
			if (result is GatewayResult.Failure) return result
			val candidate = (result as GatewayResult.Success).value
			beforeIdentityAdmission()
			val profileLease = coordinator.admit(revision) ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			val verification = try { verifier.verify(candidate, profileLease) } catch (error: CancellationException) { throw error } catch (_: Exception) { SessionVerification.Retry } finally { coordinator.finish(profileLease) }
			if (candidate.role != "student" || verification !is SessionVerification.Verified || !verification.pupil) return GatewayResult.Failure(AppError.UNAVAILABLE)
			return try {
				val active = synchronized(StorageLock) {
					val activated = coordinator.activateIfUnchanged(revision, verification.account) {
						session = null
						remote = null
						scheduleSession = null
						diarySession = null
					} ?: return@synchronized null
					session = activated
					remote = candidate
					scheduleSession = verification.scheduleSession
					diarySession = verification.diarySession
					activated
				} ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
				try {
					emit(DiaryState.Cleared)
					synchronized(StorageLock) {
						if (coordinator.ifActive(active) { session === active } != true) return@synchronized GatewayResult.Failure(AppError.UNAVAILABLE)
						val material = encodeSession(candidate)
						try { check(store.write(active, material)) } finally { material.fill(0) }
						GatewayResult.Success(Unit)
					}
				} catch (error: Exception) {
					synchronized(StorageLock) {
						if (session === active) { session = null; remote = null; scheduleSession = null; diarySession = null }
						try { coordinator.revokeIfActive(active) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
					}
					throw error
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
		} finally {
			password.fill('\u0000')
			synchronized(StorageLock) {
				if (loginRevision == revision) loginRevision = null
			}
		}
	}

	fun restore(): RestoreResult {
		var candidate: UnverifiedSession? = null
		var scheduleCandidate: VerifiedScheduleSession? = null
		var diaryCandidate: VerifiedDiarySession? = null
		return store.restoreLeased({ beforeIdentityAdmission() }, { material, lease ->
			val remote = decodeSession(material) ?: return@restoreLeased SessionVerification.Invalid
			val verification = verifier.verify(remote, lease)
			if (verification is SessionVerification.Verified && verification.pupil) { candidate = remote; scheduleCandidate = verification.scheduleSession; diaryCandidate = verification.diarySession }
			verification
		}) { active ->
			try {
				val installed = coordinator.ifActive(active) {
					session = active
					remote = candidate
					scheduleSession = scheduleCandidate
					diarySession = diaryCandidate
					true
				} == true
				if (installed) {
					afterRestoreActivation()
					if (coordinator.ifActive(active) { session === active } == true) emit(DiaryState.Cleared)
				}
			} catch (error: Exception) {
				synchronized(StorageLock) {
					if (session === active) { session = null; remote = null; scheduleSession = null; diarySession = null }
				}
				throw error
			}
		}
	}

	fun currentWeek(deliver: (GatewayResult<String>) -> Unit) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++weekRevision
			val candidate = remote
			val verified = diarySession?.takeIf { candidate != null && candidate.identityCandidate == active.account.value && it.remote === candidate && it.account == active.account.value }
			Triple(verified, revision, requestRevision)
		} ?: return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val result = if (request.first == null) GatewayResult.Failure(AppError.UNAVAILABLE) else try { gateway.currentWeek(request.first!!, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			val outcome = coordinator.ifActive(active) {
				if (lease.cancelled || request.second != weekRevision) null
				else if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
					remote = null; scheduleSession = null; diarySession = null; session = null
					Pair(true, runCatching { coordinator.revokeIfActive(active, expired = true) })
				} else Pair(false, null)
			}
			if (outcome != null) {
				if (outcome.first) {
					try { outcome.second!!.getOrThrow() } finally { emit(DiaryState.Error(AppError.SESSION_EXPIRED)) }
				} else deliver(result)
			}
		} finally { coordinator.finish(lease) }
	}

	fun schedule(date: String, deliver: (GatewayResult<Schedule>) -> Unit) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++scheduleRevision
			val candidate = remote
			val verified = scheduleSession?.takeIf { candidate != null && candidate.identityCandidate == active.account.value && it.remote === candidate && it.account == active.account.value }
			Triple(verified, revision, requestRevision)
		} ?: return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val result = if (request.first == null) GatewayResult.Failure(AppError.UNAVAILABLE) else try { gateway.schedule(request.first!!, date, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			val outcome = coordinator.ifActive(active) {
				if (lease.cancelled || request.second != scheduleRevision) null
				else if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
					remote = null; scheduleSession = null; diarySession = null; session = null
					Pair(true, runCatching { coordinator.revokeIfActive(active, expired = true) })
				} else Pair(false, null)
			}
			if (outcome != null) {
				if (outcome.first) {
					try { outcome.second!!.getOrThrow() } finally { emit(DiaryState.Error(AppError.SESSION_EXPIRED)) }
				} else deliver(result)
			}
		} finally { coordinator.finish(lease) }
	}

	fun refreshCurrent() {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++refreshRevision
			val candidate = remote
			val proof = diarySession?.takeIf { candidate != null && it.remote === candidate && it.account == active.account.value && candidate.identityCandidate == active.account.value }
			if (proof == null) {
				emit(DiaryState.Error(AppError.UNAVAILABLE))
				return@ifActive null
			}
			Triple(proof, revision, requestRevision)
		} ?: return
		if (coordinator.ifActive(active) { request.second == refreshRevision && request.third == coordinator.requestRevision() } != true) return
		emit(DiaryState.Loading)
		if (coordinator.ifActive(active) { request.second == refreshRevision } != true) return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val week = try { gateway.currentWeek(request.first, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			if (week is GatewayResult.Failure) {
				coordinator.ifActive(active) {
					if (!lease.cancelled && request.second == refreshRevision) {
						if (week.error == AppError.SESSION_EXPIRED) {
							remote = null; scheduleSession = null; diarySession = null; session = null
							coordinator.revokeIfActive(active, expired = true)
							emit(DiaryState.Error(week.error))
						} else if (week.error == AppError.OFFLINE || week.error == AppError.TIMEOUT) {
							try {
								val context = request.first.remote.context!!.diary("")
								val saved = cache.read(active)?.let { decode(it, context) }
								emit(if (saved == null) DiaryState.Error(week.error) else DiaryState.Ready(saved, week.error))
							} catch (_: Exception) { emit(DiaryState.Error(AppError.CACHE_UNREADABLE)) }
						} else {
							emit(DiaryState.Error(week.error))
						}
					}
				}
				return
			}
			val date = (week as GatewayResult.Success).value
			val context = request.first.remote.context?.diary(date) ?: return
			val allowed = coordinator.ifActive(active) { !lease.cancelled && request.second == refreshRevision } == true
			if (!allowed) return
			val result = try { gateway.diary(request.first, context, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			coordinator.ifActive(active) {
				if (lease.cancelled || request.second != refreshRevision) return@ifActive
				if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
					remote = null; scheduleSession = null; diarySession = null; session = null
					coordinator.revokeIfActive(active, expired = true)
					emit(DiaryState.Error(AppError.SESSION_EXPIRED))
					return@ifActive
				}
				try {
					when (result) {
						is GatewayResult.Success -> if (result.value.date != date) emit(failureState(active, context, AppError.INCOMPATIBLE_RESPONSE)) else {
							val snapshot = DiarySnapshot(result.value, clock())
							check(cache.write(active, encode(context, snapshot)))
							if (!lease.cancelled && coordinator.ifActive(active) { request.second == refreshRevision } == true) emit(DiaryState.Ready(snapshot))
						}
						is GatewayResult.Failure -> emit(failureState(active, context, result.error))
					}
				} catch (error: CancellationException) { throw error } catch (_: Exception) {
					if (!lease.cancelled && coordinator.ifActive(active) { request.second == refreshRevision } == true) emit(DiaryState.Error(AppError.CACHE_UNREADABLE))
				}
			}
		} finally { coordinator.finish(lease) }
	}

	fun refresh(context: DiaryContext) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++refreshRevision
			val authenticated = remote
			val proof = diarySession
			if (authenticated == null || proof == null || proof.remote !== authenticated || proof.account != active.account.value || authenticated.identityCandidate != active.account.value || !proof.permits(context)) {
				emit(DiaryState.Error(AppError.UNAVAILABLE))
				return@ifActive null
			}
			Triple(proof, revision, requestRevision)
		} ?: return
		if (coordinator.ifActive(active) { request.second == refreshRevision && request.third == coordinator.requestRevision() } != true) return
		emit(DiaryState.Loading)
		if (coordinator.ifActive(active) { request.second == refreshRevision } != true) return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val result = try { gateway.diary(request.first, context, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			coordinator.ifActive(active) {
				if (lease.cancelled || request.second != refreshRevision) return@ifActive
				if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
					remote = null
					scheduleSession = null
					diarySession = null
					session = null
					coordinator.revokeIfActive(active, expired = true)
					emit(DiaryState.Error(AppError.SESSION_EXPIRED))
					return@ifActive
				}
				try {
					when (result) {
						is GatewayResult.Success -> {
							if (result.value.date != context.date) {
								emit(failureState(active, context, AppError.INCOMPATIBLE_RESPONSE))
							} else {
								val snapshot = DiarySnapshot(result.value, clock())
								check(cache.write(active, encode(context, snapshot)))
								if (!lease.cancelled && coordinator.ifActive(active) { request.second == refreshRevision } == true) emit(DiaryState.Ready(snapshot))
							}
						}
						is GatewayResult.Failure -> emit(failureState(active, context, result.error))
					}
				} catch (error: CancellationException) { throw error } catch (_: Exception) {
					if (!lease.cancelled && coordinator.ifActive(active) { request.second == refreshRevision } == true) emit(DiaryState.Error(AppError.CACHE_UNREADABLE))
				}
			}
		} finally { coordinator.finish(lease) }
	}

	private fun failureState(active: ActiveSession, context: DiaryContext, error: AppError): DiaryState {
		val saved = cache.read(active)?.let { decode(it, context) }?.takeIf { it.diary.date == context.date }
		return if (saved == null) DiaryState.Error(error) else DiaryState.Ready(saved, error)
	}

	fun cancelInFlightNow() = coordinator.cancelInFlightNow()
	fun pendingLogoutTicket() = coordinator.pendingLogoutTicket()
	internal fun observeRevocations(observer: (Long) -> Unit) = coordinator.observeRevocations(observer)
	internal fun removeRevocationObserver(observer: (Long) -> Unit) = coordinator.removeRevocationObserver(observer)
	internal fun revocationEpoch() = coordinator.revocationEpoch()
	internal fun sessionEvent() = coordinator.event()
	internal fun ownedEpoch() = coordinator.ownedEpoch(session)

	fun logout(token: Any? = null) {
		val outcome: Result<Boolean> = synchronized(StorageLock) {
			if (token != null && !coordinator.isPendingLogout(token)) return@synchronized Result.success(false)
			runCatching {
				if (token != null) {
					loginRevision = null
					session = null
					remote = null
					scheduleSession = null
					diarySession = null
					coordinator.logout(token)
				} else {
					val pending = loginRevision
					loginRevision = null
					val active = session
					if (active == null) {
						if (!coordinator.logoutIfNoActive()) pending?.let { coordinator.cancelLogin(it) }
					} else {
						remote = null
						scheduleSession = null
						diarySession = null
						session = null
						val revoked = coordinator.revokeIfActive(active)
						if (!revoked) coordinator.logoutIfNoActive()
						pending?.let { coordinator.cancelLogin(it) }
					}
				}
				true
			}
		}
		if (outcome.isFailure || outcome.getOrNull() == true) emit(DiaryState.Cleared)
		outcome.getOrThrow()
	}
}

private fun encodeSession(session: UnverifiedSession): ByteArray {
	val bytes = ByteArrayOutputStream()
	DataOutputStream(bytes).use { output ->
		output.writeInt(4)
		output.writeUTF(session.identityCandidate ?: throw IllegalArgumentException())
		output.writeUTF(session.token)
		output.writeBoolean(session.context != null)
		session.context?.let {
			output.writeLong(it.schoolId); output.writeInt(it.eduYear); output.writeLong(it.groupId)
			output.writeBoolean(it.studentSchoolUuid != null)
			it.studentSchoolUuid?.let(output::writeUTF)
		}
	}
	return bytes.toByteArray()
}

private fun decodeSession(bytes: ByteArray): UnverifiedSession? = try {
	val input = DataInputStream(ByteArrayInputStream(bytes))
	val version = input.readInt()
	require(version == 2 || version == 3 || version == 4)
	val identity = input.readUTF().also { require(it.isNotEmpty()) }
	val token = input.readUTF().also { require(it.isNotEmpty()) }
	val context = if (input.readBoolean()) {
		val school = input.readLong(); val year = input.readInt(); val group = input.readLong()
		val uuid = if (version >= 3 && input.readBoolean()) input.readUTF() else null
		org.materialclass.api.SchoolContext(school, year, group, if (version == 4) uuid else null)
	} else null
	require(input.available() == 0)
	UnverifiedSession(token, identity, "student", context)
} catch (_: Exception) { null }

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
