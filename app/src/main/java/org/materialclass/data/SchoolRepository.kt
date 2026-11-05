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
import org.materialclass.api.ScheduleDay
import org.materialclass.api.ScheduleLesson
import org.materialclass.api.SchoolContext
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

// Linearize completion after parsing and before returning to the repository.
private class CompletingIdentityVerifier(private val delegate: IdentityVerifier) : IdentityVerifier {
	override fun verify(session: UnverifiedSession, lease: RequestLease): SessionVerification =
		lease.completeVerification(delegate.verify(session, lease))
}

internal interface DiaryCache {
	fun read(session: ActiveSession): ByteArray?
	fun write(session: ActiveSession, bytes: ByteArray): Boolean
}

internal class EncryptedDiaryCache(private val cache: EncryptedCache) : DiaryCache {
	override fun read(session: ActiveSession): ByteArray? = cache.read(session)
	override fun write(session: ActiveSession, bytes: ByteArray): Boolean = cache.write(session, bytes)
}

internal data class ScheduleSnapshot(val schedule: Schedule, val date: String, val lastSuccessMillis: Long, val staleError: AppError? = null, val savedPeriod: Boolean = false)

private fun ScheduleSnapshot.withoutCurrentDay(error: AppError): ScheduleSnapshot {
	val days = schedule.days.map { ScheduleDay(it.date, it.dateFormat, false, it.isHoliday, it.lessons) }
	return copy(schedule = Schedule(days), staleError = error)
}

internal data class DiarySnapshot(val diary: Diary, val lastSuccessMillis: Long)

internal sealed class DiaryState {
	data object Loading : DiaryState()
	data object Cleared : DiaryState()
	// Validate at dequeue; invoking an arbitrary callback does not make delivery atomic.
	data class RestoreClear(val active: ActiveSession, val epoch: Long) : DiaryState()
	data class Ready(val snapshot: DiarySnapshot, val staleError: AppError? = null, val guard: DiaryGuard? = null) : DiaryState()
	data class Error(val error: AppError, val guard: DiaryGuard? = null) : DiaryState()
}

internal data class DiaryGuard(val epoch: Long, val revision: Long, val requestRevision: Long, val active: ActiveSession, val expired: Boolean)

internal data class AnnualGradesDelivery(val result: GatewayResult<org.materialclass.api.AnnualGrades>, val active: ActiveSession, val epoch: Long, val revision: Long, val requestRevision: Long)

internal class SchoolRepository(
	private val gateway: SchoolGateway,
	verifier: IdentityVerifier,
	private val coordinator: SessionCoordinator,
	private val cache: DiaryCache,
	private val clock: () -> Long,
	private val emit: (DiaryState) -> Unit,
	private val store: SessionStore,
	private val scheduleCache: DiaryCache? = null,
	private val beforeSavedScheduleDelivery: () -> Unit = {},
) {
	private val verifier = CompletingIdentityVerifier(verifier)
	@Volatile private var session: ActiveSession? = null
	private var remote: UnverifiedSession? = null
	private var scheduleSession: VerifiedScheduleSession? = null
	private var diarySession: VerifiedDiarySession? = null
	private var profile: org.materialclass.api.StudentProfile? = null
	private var verifiedScheduleRemote: UnverifiedSession? = null
	private var refreshRevision = 0L
	private var profileRevision = 0L
	private var scheduleRevision = 0L
	private var weekRevision = 0L
	private var gradesRevision = 0L
	private var verifiedWeek: Triple<ActiveSession, Long, String>? = null
	@Volatile private var loginRevision: Long? = null
	@Volatile private var diaryPublished = false
	internal var afterRestoreActivation: () -> Unit = {}
	internal var beforeRequestAdmission: () -> Unit = {}
	internal var beforeIdentityAdmission: () -> Unit = {}
	internal var beforeProfileReceipt: (SessionVerification, RequestLease) -> Unit = { _, _ -> }
	internal var afterProfileVerification: (SessionVerification, RequestLease) -> Unit = { _, _ -> }

	fun login(username: String, password: CharArray): GatewayResult<Unit> {
		var revision: Long? = null
		try {
			val currentRevision = synchronized(StorageLock) {
				coordinator.beginLogin().also { loginRevision = it; revision = it }
			}
			beforeIdentityAdmission()
			val lease = coordinator.admit(currentRevision) ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			val result = try { gateway.login(username, password, lease) } catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				GatewayResult.Failure(AppError.UNAVAILABLE)
			} finally { password.fill('\u0000'); coordinator.finish(lease) }
			if (result is GatewayResult.Failure) return result
			val candidate = (result as GatewayResult.Success).value
			beforeIdentityAdmission()
			val profileLease = coordinator.admit(currentRevision) ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			val verification = try { verifier.verify(candidate, profileLease) } catch (error: CancellationException) { throw error } catch (_: Exception) { SessionVerification.Retry } finally { coordinator.finish(profileLease) }
			if (candidate.role != "student" || verification !is SessionVerification.Verified || !verification.pupil) return GatewayResult.Failure(AppError.UNAVAILABLE)
			var prior: ActiveSession? = null
			return try {
				val active = synchronized(StorageLock) {
					val activated = coordinator.activateIfUnchanged(currentRevision, verification.account) {
						prior = session
						session = null
						remote = null
						scheduleSession = null
						diarySession = null; profile = null
					} ?: return@synchronized null
					session = activated
					remote = candidate
					scheduleSession = verification.scheduleSession
					verifiedScheduleRemote = if (scheduleSession != null) candidate else null
					diarySession = verification.diarySession
					profile = verification.profile
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
						if (session === active) { session = null; remote = null; scheduleSession = null; diarySession = null; profile = null }
						try { coordinator.revokeIfActive(active) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
					}
					throw error
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) {
				val event = coordinator.event()
				if (diaryPublished && prior != null && event.change == org.materialclass.session.SessionChange.LOGOUT && event.session === prior && coordinator.revocationEpoch() == event.epoch) {
					emit(DiaryState.Cleared)
				}
				GatewayResult.Failure(AppError.UNAVAILABLE)
			}
		} finally {
			password.fill('\u0000')
			synchronized(StorageLock) {
				if (revision != null && loginRevision == revision) loginRevision = null
			}
		}
	}

	fun restore(): RestoreResult {
		var candidate: UnverifiedSession? = null
		var scheduleCandidate: VerifiedScheduleSession? = null
		var diaryCandidate: VerifiedDiarySession? = null
		var profileCandidate: org.materialclass.api.StudentProfile? = null
		return store.restoreLeased({ beforeIdentityAdmission() }, { material, lease ->
			val remote = decodeSession(material) ?: return@restoreLeased SessionVerification.Invalid
			val verification = verifier.verify(remote, lease)
			if (verification is SessionVerification.Verified && verification.pupil) { candidate = remote; scheduleCandidate = verification.scheduleSession; diaryCandidate = verification.diarySession; profileCandidate = verification.profile }
			verification
		}) { active ->
			try {
				val installed = coordinator.ifActive(active) {
					session = active
					remote = candidate
					scheduleSession = scheduleCandidate
					verifiedScheduleRemote = if (scheduleCandidate != null) candidate else null
					diarySession = diaryCandidate
					profile = profileCandidate
					true
				} == true
				if (installed) {
					afterRestoreActivation()
					val clear = coordinator.ifActive(active) {
						if (session === active) coordinator.ownedEpoch(active)?.let { DiaryState.RestoreClear(active, it) } else null
					}
					if (clear != null) emit(clear)
				}
			} catch (error: Exception) {
				synchronized(StorageLock) {
					if (session === active) { session = null; remote = null; scheduleSession = null; diarySession = null; profile = null }
				}
				throw error
			}
		}
	}

	internal fun currentProfile(): org.materialclass.api.StudentProfile? {
		val active = session ?: return null
		return coordinator.ifActive(active) { if (session === active) profile else null }
	}

	internal fun beginProfileRefresh(): Long = synchronized(StorageLock) { ++profileRevision }

	internal fun refreshProfile(revision: Long, deliver: (org.materialclass.api.StudentProfile?, Boolean) -> Unit): Boolean {
		val active = session ?: return false
		val request = coordinator.ifActive(active) {
			val candidate = remote ?: return@ifActive null
			Triple(candidate, coordinator.requestRevision(), coordinator.loginGeneration())
		} ?: return false
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.second) ?: return false
		try {
			val verification = try { verifier.verify(request.first, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { SessionVerification.Retry }
			beforeProfileReceipt(verification, lease)
			val received = lease.trustedVerification() === verification
			afterProfileVerification(verification, lease)
			val outcome = coordinator.ifActive(active) {
				val contradiction = verification == SessionVerification.Invalid || verification == SessionVerification.Expired ||
					(verification is SessionVerification.Verified && (!verification.pupil || verification.account != active.account))
				if (received && contradiction && session === active && remote === request.first && request.third == coordinator.loginGeneration()) {
					if (coordinator.revokeIfActive(active, expired = true)) {
						remote = null; scheduleSession = null; diarySession = null; profile = null; session = null
					}
					null
				} else if (lease.cancelled || session !== active || remote !== request.first || request.second != coordinator.requestRevision() || revision != profileRevision) null
				else {
					val verified = verification as? SessionVerification.Verified
					scheduleSession = verified?.scheduleSession?.takeIf { it.remote === request.first && it.account == active.account.value }
					val lostVerifiedUuid = verifiedScheduleRemote === request.first && scheduleSession == null
					if (scheduleSession != null) verifiedScheduleRemote = request.first
					diarySession = verified?.diarySession?.takeIf { !lostVerifiedUuid && it.remote === request.first && it.account == active.account.value }
					profile = if (diarySession != null) verified?.profile else null
					val usable = diarySession != null
					if (!usable || scheduleSession == null) {
						coordinator.invalidateSchoolRequests(active, lease)
						refreshRevision++; scheduleRevision++; weekRevision++; gradesRevision++
						verifiedWeek = null
					}
					Triple(profile, usable, scheduleSession != null)
				}
			}
			if (outcome != null) deliver(outcome.first, outcome.second && outcome.third)
			return outcome?.second == true
		} finally { coordinator.finish(lease) }
	}

	fun currentWeek(deliver: (GatewayResult<String>) -> Unit) = currentWeekForSchedule { result, _ -> deliver(result) }

	fun annualGrades(deliver: (AnnualGradesDelivery) -> Unit) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val candidate = remote
			val proof = diarySession?.takeIf { candidate != null && it.remote === candidate && it.account == active.account.value && candidate.identityCandidate == active.account.value }
			Triple(proof, ++gradesRevision, coordinator.requestRevision())
		} ?: return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val result = try {
				val source = gateway as? org.materialclass.api.AnnualGradesGateway
				if (request.first == null || source == null) GatewayResult.Failure(AppError.UNAVAILABLE) else source.annualGrades(request.first!!, lease)
			} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			val delivery = coordinator.ifActive(active) {
				if (lease.cancelled || request.second != gradesRevision || request.third != coordinator.requestRevision()) null
				else if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
					remote = null; scheduleSession = null; diarySession = null; session = null
					coordinator.revokeIfActive(active, expired = true)
					null
				} else AnnualGradesDelivery(result, active, coordinator.revocationEpoch(), request.second, request.third)
			}
			if (delivery != null) deliver(delivery)
		} finally { coordinator.finish(lease) }
	}

	internal fun acceptsAnnualGrades(delivery: AnnualGradesDelivery): Boolean = synchronized(StorageLock) {
		delivery.epoch == coordinator.revocationEpoch() && delivery.revision == gradesRevision && delivery.requestRevision == coordinator.requestRevision() && coordinator.ifActive(delivery.active) { session === delivery.active } == true
	}

	fun currentWeekForSchedule(deliver: (GatewayResult<String>, Long) -> Unit) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++weekRevision
			verifiedWeek = null
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
					remote = null; scheduleSession = null; diarySession = null; profile = null; session = null
					Pair(true, runCatching { coordinator.revokeIfActive(active, expired = true) })
				} else {
					if (result is GatewayResult.Success && request.first != null && request.third == coordinator.requestRevision()) verifiedWeek = Triple(active, request.second, result.value)
					Pair(false, null)
			}
			}
			if (outcome != null) {
				if (outcome.first) {
					try { outcome.second!!.getOrThrow() } finally { emit(DiaryState.Error(AppError.SESSION_EXPIRED)) }
				} else if (coordinator.ifActive(active) { !lease.cancelled && request.second == weekRevision && request.third == coordinator.requestRevision() } == true) deliver(result, request.second)
			}
		} finally { coordinator.finish(lease) }
	}

	fun schedule(date: String, deliver: (GatewayResult<Schedule>) -> Unit) = scheduleSnapshot(date) { result ->
		deliver(when (result) {
			is GatewayResult.Success -> GatewayResult.Success(result.value.schedule)
			is GatewayResult.Failure -> result
		})
	}

	fun scheduleSnapshot(date: String, deliver: (GatewayResult<ScheduleSnapshot>) -> Unit) {
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
					remote = null; scheduleSession = null; diarySession = null; profile = null; session = null
					Pair(true, runCatching { coordinator.revokeIfActive(active, expired = true) })
				} else Pair(false, null)
			}
			if (outcome != null) {
				if (outcome.first) {
					try { outcome.second!!.getOrThrow() } finally { emit(DiaryState.Error(AppError.SESSION_EXPIRED)) }
				} else {
					val converted = coordinator.ifActive(active) {
						if (lease.cancelled || request.second != scheduleRevision || request.third != coordinator.requestRevision()) return@ifActive null
						val context = request.first?.remote?.context
						try {
							when (result) {
								is GatewayResult.Success -> {
									val snapshot = ScheduleSnapshot(result.value, date, clock())
									if (context != null && scheduleCache != null) {
										val proof = verifiedWeek
										val proven = proof != null && proof.first === active && proof.second == weekRevision && proof.third == date && validSchedule(snapshot)
										check(scheduleCache.write(active, encodeSchedule(context, snapshot, proven)))
									}
									GatewayResult.Success(snapshot)
								}
								is GatewayResult.Failure -> {
									val saved = if (context != null && (result.error == AppError.OFFLINE || result.error == AppError.TIMEOUT)) scheduleCache?.read(active)?.let { decodeSchedule(it, context, date, false) } else null
									if (saved == null) result else GatewayResult.Success(saved.withoutCurrentDay(result.error))
								}
							}
						} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.CACHE_UNREADABLE) }
					}
					if (converted != null && !lease.cancelled && coordinator.ifActive(active) { request.second == scheduleRevision && request.third == coordinator.requestRevision() } == true) deliver(converted)
				}
			}
		} finally { coordinator.finish(lease) }
	}


	fun savedSchedule(error: AppError, expectedWeek: Long, deliver: (GatewayResult<ScheduleSnapshot>) -> Unit) {
		if (error != AppError.OFFLINE && error != AppError.TIMEOUT) { deliver(GatewayResult.Failure(error)); return }
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			if (weekRevision != expectedWeek) return@ifActive null
			val candidate = remote
			val scheduleProof = scheduleSession?.takeIf { candidate != null && it.remote === candidate && it.account == active.account.value && candidate.identityCandidate == active.account.value }
			val diaryProof = diarySession?.takeIf { candidate != null && it.remote === candidate && it.account == active.account.value }
			val context = if (scheduleProof != null && diaryProof != null) candidate?.context else null
			val revision = ++scheduleRevision
			val saved = if (context == null) null else try { scheduleCache?.read(active)?.let { decodeSchedule(it, context, null, true) } } catch (_: Exception) { null }
			Triple(saved, revision, Pair(weekRevision, coordinator.requestRevision()))
		} ?: return
		val saved = request.first
		val result = if (saved == null) GatewayResult.Failure(error) else GatewayResult.Success(saved.withoutCurrentDay(error).copy(savedPeriod = true))
		if (coordinator.ifActive(active) { request.second == scheduleRevision && request.third.first == weekRevision && request.third.second == coordinator.requestRevision() } == true) {
			beforeSavedScheduleDelivery()
			deliver(result)
		}
	}

	private fun guarded(state: DiaryState, active: ActiveSession, revision: Long, requestRevision: Long, expired: Boolean = false): DiaryState {
		val guard = DiaryGuard(coordinator.revocationEpoch(), revision, requestRevision, active, expired)
		return when (state) {
			is DiaryState.Ready -> state.copy(guard = guard)
			is DiaryState.Error -> state.copy(guard = guard)
			else -> state
		}
	}

	internal fun acceptsDiary(state: DiaryState): Boolean {
		if (state is DiaryState.RestoreClear) return synchronized(StorageLock) {
			session === state.active && coordinator.ownedEpoch(state.active) == state.epoch
		}
		val guard = when (state) {
			is DiaryState.Ready -> state.guard
			is DiaryState.Error -> state.guard
			else -> null
		} ?: return true
		return synchronized(StorageLock) {
			val event = coordinator.event()
			guard.epoch == event.epoch && guard.revision == refreshRevision && guard.requestRevision == coordinator.requestRevision() &&
				if (guard.expired) event.change == org.materialclass.session.SessionChange.EXPIRED && event.session === guard.active
				else coordinator.ifActive(guard.active) { session === guard.active } == true
		}
	}

	fun refreshCurrent(onWeeks: (org.materialclass.api.DiaryWeeks) -> Unit = {}) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++refreshRevision
			val candidate = remote
			val proof = diarySession?.takeIf { candidate != null && it.remote === candidate && it.account == active.account.value && candidate.identityCandidate == active.account.value }
			Triple(proof, revision, requestRevision)
		} ?: return
		val proof = request.first
		if (proof == null) {
			val state = coordinator.ifActive(active) {
				if (request.second != refreshRevision || request.third != coordinator.requestRevision()) null
				else guarded(DiaryState.Error(AppError.UNAVAILABLE), active, request.second, request.third)
			}
			if (state != null) emit(state)
			return
		}
		if (coordinator.ifActive(active) { request.second == refreshRevision && request.third == coordinator.requestRevision() } != true) return
		emit(DiaryState.Loading)
		if (coordinator.ifActive(active) { request.second == refreshRevision } != true) return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val week = try {
				if (gateway is org.materialclass.api.DiaryWeeksGateway) gateway.diaryWeeks(proof, lease)
				else when (val result = gateway.currentWeek(proof, lease)) {
					is GatewayResult.Success -> GatewayResult.Success(org.materialclass.api.DiaryWeeks(result.value, emptyList()))
					is GatewayResult.Failure -> result
				}
			} catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			if (week is GatewayResult.Failure) {
				val state = coordinator.ifActive(active) {
					if (lease.cancelled || request.second != refreshRevision) return@ifActive null
					val expired = week.error == AppError.SESSION_EXPIRED
					val value = if (expired) {
						remote = null; scheduleSession = null; diarySession = null; profile = null; session = null
						coordinator.revokeIfActive(active, expired = true)
						DiaryState.Error(week.error)
					} else DiaryState.Error(week.error)
					guarded(value, active, request.second, request.third, expired)
				}
				if (state != null) emit(state)
				return
			}
			val weeks = (week as GatewayResult.Success).value
			val date = weeks.currentWeek
			val context = proof.remote.context?.diary(date) ?: return
			if (coordinator.ifActive(active) { !lease.cancelled && request.second == refreshRevision && request.third == coordinator.requestRevision() } != true) return
			onWeeks(weeks)
			val result = try { gateway.diary(proof, context, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			val state = diaryResult(active, context, result, lease, request.second, request.third)
			if (state is DiaryState.Ready) diaryPublished = true
			if (state != null) emit(state)
		} finally { coordinator.finish(lease) }
	}

	fun refreshDate(date: String) {
		val active = session ?: return
		val context = coordinator.ifActive(active) { remote?.context?.diary(date) } ?: return
		refresh(context)
	}

	fun refresh(context: DiaryContext) {
		val active = session ?: return
		val request = coordinator.ifActive(active) {
			val requestRevision = coordinator.requestRevision()
			val revision = ++refreshRevision
			val authenticated = remote
			val proof = diarySession?.takeIf { authenticated != null && it.remote === authenticated && it.account == active.account.value && authenticated.identityCandidate == active.account.value && it.permits(context) }
			Triple(proof, revision, requestRevision)
		} ?: return
		val proof = request.first
		if (proof == null) {
			val state = coordinator.ifActive(active) {
				if (request.second != refreshRevision || request.third != coordinator.requestRevision()) null
				else guarded(DiaryState.Error(AppError.UNAVAILABLE), active, request.second, request.third)
			}
			if (state != null) emit(state)
			return
		}
		if (coordinator.ifActive(active) { request.second == refreshRevision && request.third == coordinator.requestRevision() } != true) return
		emit(DiaryState.Loading)
		if (coordinator.ifActive(active) { request.second == refreshRevision } != true) return
		beforeRequestAdmission()
		val lease = coordinator.admit(active, request.third) ?: return
		try {
			val result = try { gateway.diary(proof, context, lease) } catch (error: CancellationException) { throw error } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) }
			val state = diaryResult(active, context, result, lease, request.second, request.third)
			if (state is DiaryState.Ready) diaryPublished = true
			if (state != null) emit(state)
		} finally { coordinator.finish(lease) }
	}

	private fun diaryResult(active: ActiveSession, context: DiaryContext, result: GatewayResult<Diary>, lease: RequestLease, revision: Long, requestRevision: Long): DiaryState? = coordinator.ifActive(active) {
		if (lease.cancelled || revision != refreshRevision) return@ifActive null
		if (result is GatewayResult.Failure && result.error == AppError.SESSION_EXPIRED) {
			remote = null; scheduleSession = null; diarySession = null; profile = null; session = null
			coordinator.revokeIfActive(active, expired = true)
			return@ifActive guarded(DiaryState.Error(AppError.SESSION_EXPIRED), active, revision, requestRevision, true)
		}
		val state = try {
			when (result) {
				is GatewayResult.Success -> if (result.value.date != context.date) failureState(active, context, AppError.INCOMPATIBLE_RESPONSE) else {
					val snapshot = DiarySnapshot(result.value, clock())
					check(cache.write(active, encode(context, snapshot)))
					DiaryState.Ready(snapshot)
				}
				is GatewayResult.Failure -> failureState(active, context, result.error)
			}
		} catch (error: CancellationException) { throw error } catch (_: Exception) { DiaryState.Error(AppError.CACHE_UNREADABLE) }
		if (lease.cancelled || coordinator.ifActive(active) { revision == refreshRevision } != true) null
		else guarded(state, active, revision, requestRevision)
	}

	private fun failureState(active: ActiveSession, context: DiaryContext, error: AppError): DiaryState {
		if (error != AppError.OFFLINE && error != AppError.TIMEOUT) return DiaryState.Error(error)
		val saved = cache.read(active)?.let { decode(it, context) }?.takeIf { it.diary.date == context.date }
		return if (saved == null) DiaryState.Error(error) else DiaryState.Ready(saved, error)
	}

	fun cancelInFlightNow() = coordinator.cancelInFlightNow()
	fun pendingLogoutTicket() = coordinator.pendingLogoutTicket()
	internal fun observeRevocations(observer: (Long) -> Unit) = coordinator.observeRevocations(observer)
	internal fun removeRevocationObserver(observer: (Long) -> Unit) = coordinator.removeRevocationObserver(observer)
	internal fun observeUiRevocations(observer: (Long, () -> Unit) -> Unit) = coordinator.observeUiRevocations(observer)
	internal fun failUiRevocation(acknowledge: () -> Unit) = coordinator.failUiRevocation(acknowledge)
	internal fun removeUiRevocationObserver(observer: (Long, () -> Unit) -> Unit) = coordinator.removeUiRevocationObserver(observer)
	internal fun revocationEpoch() = coordinator.revocationEpoch()
	internal fun sessionEvent() = coordinator.event()
	internal fun ownedEpoch() = coordinator.ownedEpoch(session)

	fun logout(token: Any? = null) {
		val nested = Thread.holdsLock(StorageLock)
		var acknowledgement: java.util.concurrent.CountDownLatch? = null
		val selected: (Any) -> Unit = { acknowledgement = coordinator.uiAcknowledgement(it) }
		val outcome: Result<Boolean> = synchronized(StorageLock) {
			if (token != null && !coordinator.isPendingLogout(token)) return@synchronized Result.success(false)
			runCatching {
				if (token != null) {
					loginRevision = null
					session = null
					remote = null
					scheduleSession = null
					diarySession = null; profile = null
					coordinator.logout(token, selected)
				} else {
					val pending = loginRevision
					loginRevision = null
					val active = session
					if (active == null) {
						if (!coordinator.logoutIfNoActive(selected = selected)) pending?.let { coordinator.cancelLogin(it) }
					} else {
						remote = null
						scheduleSession = null
						diarySession = null; profile = null
						session = null
						val revoked = coordinator.revokeIfActive(active, selected = selected)
						if (!revoked) coordinator.logoutIfNoActive(selected = selected)
						pending?.let { coordinator.cancelLogin(it) }
					}
				}
				true
			}
		}
		if (outcome.isFailure || outcome.getOrNull() == true) emit(DiaryState.Cleared)
		if (!nested && outcome.isSuccess) coordinator.awaitUiAcknowledgement(acknowledgement)
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


private fun encodeSchedule(context: SchoolContext, snapshot: ScheduleSnapshot, proven: Boolean): ByteArray {
	val bytes = ByteArrayOutputStream()
	DataOutputStream(bytes).use { out ->
		out.writeInt(2); out.writeBoolean(proven); out.writeLong(context.schoolId); out.writeInt(context.eduYear); out.writeLong(context.groupId)
		out.writeUTF(context.studentSchoolUuid ?: ""); out.writeUTF(snapshot.date); out.writeLong(snapshot.lastSuccessMillis)
		out.writeInt(snapshot.schedule.days.size)
		snapshot.schedule.days.forEach { day ->
			out.writeLong(day.date); out.writeUTF(day.dateFormat); out.writeBoolean(day.isCurrent); out.writeBoolean(day.isHoliday); out.writeInt(day.lessons.size)
			day.lessons.forEach { out.writeUTF(it.type); out.writeLong(it.cabinetId); out.writeUTF(it.themeLabel) }
		}
	}
	return bytes.toByteArray()
}

private fun decodeSchedule(bytes: ByteArray, context: SchoolContext, date: String?, requireProven: Boolean): ScheduleSnapshot? {
	return try {
	val input = DataInputStream(ByteArrayInputStream(bytes))
	val version = input.readInt()
	if (version != 1 && version != 2) return null
	val proven = if (version == 2) input.readBoolean() else false
	if (requireProven && !proven) return null
	val school = input.readLong(); val year = input.readInt(); val group = input.readLong(); val uuid = input.readUTF(); val savedDate = input.readUTF()
	if (school != context.schoolId || year != context.eduYear || group != context.groupId || uuid != (context.studentSchoolUuid ?: "") || (date != null && savedDate != date)) return null
	val time = input.readLong()
	fun count() = input.readInt().also { require(it in 0..10000) }
	val days = List(count()) {
		val timestamp = input.readLong(); val formatted = input.readUTF(); val current = input.readBoolean(); val holiday = input.readBoolean()
		ScheduleDay(timestamp, formatted, current, holiday, List(count()) { ScheduleLesson(input.readUTF(), input.readLong(), input.readUTF()) })
	}
	check(input.available() == 0)
	val snapshot = ScheduleSnapshot(Schedule(days), savedDate, time)
	if (requireProven && !validSchedule(snapshot)) null else snapshot
	} catch (_: Exception) { null }
}

private fun validSchedule(snapshot: ScheduleSnapshot): Boolean {
	fun parse(value: String): Long? {
		if (!value.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return null
		val format = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC") }
		val position = java.text.ParsePosition(0)
		val parsed = format.parse(value, position) ?: return null
		return if (position.index == value.length) parsed.time else null
	}
	val requested = parse(snapshot.date) ?: return false
	if (snapshot.schedule.days.size != 7) return false
	val dates = snapshot.schedule.days.map { parse(it.dateFormat) ?: return false }
	return requested in dates && dates.zipWithNext().all { (first, second) -> second - first == 86_400_000L }
}
