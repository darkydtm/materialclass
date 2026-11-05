package org.materialclass

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.materialclass.api.AppError
import org.materialclass.api.GatewayResult
import org.materialclass.api.NativeGateway
import org.materialclass.api.Schedule
import org.materialclass.cache.EncryptedCache
import org.materialclass.data.ScheduleSnapshot
import org.materialclass.data.DiaryState
import org.materialclass.data.EncryptedDiaryCache
import org.materialclass.data.NativeIdentityVerifier
import org.materialclass.data.SchoolRepository
import org.materialclass.session.RestoreResult
import org.materialclass.session.SessionChange
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import java.util.concurrent.Executors

internal data class NativeUiState(
	val signedIn: Boolean = false,
	val busy: Boolean = false,
	val logoutPending: Boolean = false,
	val diary: DiaryState = DiaryState.Cleared,
	val diaryWeeks: List<org.materialclass.api.DiaryWeekOption> = emptyList(),
	val selectedDiaryDate: String? = null,
	val schedule: Schedule? = null,
	val scheduleSnapshot: ScheduleSnapshot? = null,
	val annualGrades: GatewayResult<org.materialclass.api.AnnualGrades>? = null,
	val gradesLastSuccessMillis: Long? = null,
	val gradesStaleError: AppError? = null,
	val gradesLoading: Boolean = false,
	val profile: org.materialclass.api.StudentProfile? = null,

	val error: AppError? = null,
)

internal interface NativeUiController {
	val state: NativeUiState
	fun start()
	fun login(username: String, password: CharArray)
	fun refresh()
	fun selectDiaryWeek(date: String) {}
	fun refreshAnnualGrades() {}
	fun logout()
	fun close()
}

internal class NativeController(
	private val onState: (NativeUiState) -> Unit = {},
	private val uiDispatch: ((() -> Unit) -> Boolean) = { action -> Handler(Looper.getMainLooper()).post(action) },
	private val applyUiRevocation: ((() -> Unit) -> Unit) = { action -> action() },
	private val repositoryFactory: ((DiaryState) -> Unit) -> SchoolRepository,
) : NativeUiController {
	private val executor = Executors.newSingleThreadExecutor()
	private val logoutExecutor = Executors.newSingleThreadExecutor()
	private var logoutTicket: Any? = null
	private val handler = Handler(Looper.getMainLooper())
	@Volatile private var observedEpoch = 0L
	private fun applyRevocation(epoch: Long) {
		if (!closed && epoch == repository.revocationEpoch()) {
			if (epoch != observedEpoch) {
				val event = repository.sessionEvent()
				if (event.epoch != epoch) return
				if (event.change == SessionChange.ACTIVATED && repository.ownedEpoch() == epoch) {
					currentTaskEpoch = epoch
				} else if (event.change == SessionChange.EXPIRED && state.busy && !state.signedIn) {
					currentTaskEpoch = epoch
				} else {
					generation++
					state = NativeUiState(busy = logoutTicket != null, logoutPending = logoutTicket != null || repository.pendingLogoutTicket() != null, error = if (event.change == SessionChange.EXPIRED) AppError.SESSION_EXPIRED else null)
				}
				observedEpoch = epoch
			} else if (state.logoutPending && logoutTicket == null && repository.pendingLogoutTicket() == null) {
				state = state.copy(logoutPending = false)
			}
		}
	}
	private val observeLogout: (Long) -> Unit = { epoch ->
		handler.post { applyRevocation(epoch) }
	}
	private val observeUiLogout: (Long, () -> Unit) -> Unit = { epoch, acknowledge ->
		check(uiDispatch {
			try {
				applyUiRevocation { applyRevocation(epoch) }
				acknowledge()
			} catch (_: Exception) { repository.failUiRevocation(acknowledge) }
		})
	}

	@Volatile private var generation = 0L
	@Volatile private var profileRevision = 0L
	@Volatile private var diaryRevision = 0L
	@Volatile private var scheduleRevision = 0L
	@Volatile private var gradesRevision = 0L
	@Volatile private var currentDiaryRevision = 0L
	@Volatile private var closed = false
	private var started = false
	private var currentTaskGeneration = 0L
	@Volatile private var currentTaskEpoch = 0L
	private var stateValue by mutableStateOf(NativeUiState())
	override var state: NativeUiState
		get() = stateValue
		private set(value) { stateValue = value; onState(value) }

	private val repository: SchoolRepository = repositoryFactory { diary ->
		val task = currentTaskGeneration
		val revision = currentDiaryRevision
		post(task) {
			if (diaryRevision != revision) return@post
			if (!repository.acceptsDiary(diary)) return@post
			state = if (diary is DiaryState.Error && diary.error == AppError.SESSION_EXPIRED) NativeUiState(error = diary.error)
			else state.copy(diary = if (diary is DiaryState.RestoreClear) DiaryState.Cleared else diary, error = if (diary is DiaryState.Error) diary.error else state.error)
		}
	}

	init {
		val snapshot = repository.observeRevocations(observeLogout)
		repository.observeUiRevocations(observeUiLogout)
		observedEpoch = snapshot.first
		if (snapshot.second) state = NativeUiState(logoutPending = true)
	}

	private fun post(task: Long, action: () -> Unit) {
		val epoch = currentTaskEpoch
		handler.post { if (!closed && generation == task && repository.revocationEpoch() == epoch) action() }
	}

	override fun start() {
		if (started || closed) return
		started = true
		if (repository.pendingLogoutTicket() != null || logoutTicket != null) {
			state = NativeUiState(busy = logoutTicket != null, logoutPending = true)
			return
		}
		val task = ++generation
		val epoch = observedEpoch
		state = state.copy(busy = true)
		executor.execute {
			if (closed || generation != task) return@execute
			currentTaskGeneration = task
			currentTaskEpoch = epoch
			val result = try { repository.restore() } catch (_: Exception) { RestoreResult.Retry }
			if (result is RestoreResult.Restored) repository.ownedEpoch()?.let { currentTaskEpoch = it }
			post(task) {
				if (result is RestoreResult.Restored) observedEpoch = currentTaskEpoch
				state = state.copy(busy = false, signedIn = result is RestoreResult.Restored, profile = if (result is RestoreResult.Restored) repository.currentProfile() else null, error = if (result is RestoreResult.Retry) AppError.UNAVAILABLE else null)
				if (result is RestoreResult.Restored) refresh()
			}
		}
	}

	override fun login(username: String, password: CharArray) {
		if (closed || logoutTicket != null || repository.pendingLogoutTicket() != null) { password.fill('\u0000'); return }
		val task = ++generation
		val epoch = observedEpoch
		state = NativeUiState(busy = true)
		try {
			executor.execute {
				if (closed || generation != task) { password.fill('\u0000'); return@execute }
				currentTaskGeneration = task
			currentTaskEpoch = epoch
				val result = try { repository.login(username, password) } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) } finally { password.fill('\u0000') }
				if (result is GatewayResult.Success) repository.ownedEpoch()?.let { currentTaskEpoch = it }
				post(task) {
					if (result is GatewayResult.Success) observedEpoch = currentTaskEpoch
					state = state.copy(busy = false, signedIn = result is GatewayResult.Success, profile = if (result is GatewayResult.Success) repository.currentProfile() else null, error = (result as? GatewayResult.Failure)?.error)
					if (result is GatewayResult.Success) refresh()
				}
			}
		} catch (_: java.util.concurrent.RejectedExecutionException) { password.fill('\u0000'); state = state.copy(busy = false, error = AppError.UNAVAILABLE) }
	}

	override fun refresh() {
		if (closed || !state.signedIn) return
		val task = generation
		val revision = ++diaryRevision
		val profileRequest = ++profileRevision
		val repositoryProfileRequest = repository.beginProfileRefresh()
		val scheduleRequest = ++scheduleRevision
		val epoch = observedEpoch
		val selected = state.selectedDiaryDate
		state = state.copy(error = null)
		executor.execute {
			if (closed || generation != task || repository.revocationEpoch() != epoch) return@execute
			currentTaskGeneration = task
			currentDiaryRevision = revision
			currentTaskEpoch = epoch
			try {
				val profileUsable = repository.refreshProfile(repositoryProfileRequest) { profile, complete ->
					post(task) {
						if (profileRevision != profileRequest) return@post
						state = if (complete) state.copy(profile = profile) else {
							gradesRevision++
							state.copy(profile = profile, diary = DiaryState.Cleared, diaryWeeks = emptyList(), selectedDiaryDate = null, schedule = null, scheduleSnapshot = null, annualGrades = null, gradesLoading = false, error = AppError.UNAVAILABLE)
						}
					}
				}
				if (profileRevision != profileRequest || !profileUsable) return@execute
				if (diaryRevision == revision) {
					if (selected != null) repository.refreshDate(selected) else repository.refreshCurrent { weeks ->
						post(task) { if (diaryRevision != revision) return@post; state = state.copy(diaryWeeks = weeks.options, selectedDiaryDate = weeks.currentWeek) }
					}

				}
			} catch (_: Exception) { post(task) { if (diaryRevision != revision) return@post; if (state.error != AppError.SESSION_EXPIRED) state = state.copy(error = AppError.UNAVAILABLE) } }
			try {
				if (scheduleRevision != scheduleRequest) return@execute
				repository.currentWeekForSchedule { week, weekRevision ->
					if (closed || generation != task || scheduleRevision != scheduleRequest || repository.revocationEpoch() != epoch) return@currentWeekForSchedule
					when (week) {
						is GatewayResult.Success -> repository.scheduleSnapshot(week.value) { result ->
							post(task) { if (scheduleRevision != scheduleRequest) return@post; state = when (result) {
								is GatewayResult.Success -> state.copy(schedule = result.value.schedule, scheduleSnapshot = result.value)
								is GatewayResult.Failure -> state.copy(schedule = null, scheduleSnapshot = null, error = result.error)
							} }
						}
						is GatewayResult.Failure -> repository.savedSchedule(week.error, weekRevision) { result ->
							post(task) { if (scheduleRevision != scheduleRequest) return@post; state = when (result) {
								is GatewayResult.Success -> state.copy(schedule = result.value.schedule, scheduleSnapshot = result.value)
								is GatewayResult.Failure -> state.copy(schedule = null, scheduleSnapshot = null, error = result.error)
							} }
						}
					}
				}
			} catch (_: Exception) { post(task) { if (scheduleRevision != scheduleRequest) return@post; if (state.error != AppError.SESSION_EXPIRED) state = state.copy(error = AppError.UNAVAILABLE) } }
		}
	}

	override fun selectDiaryWeek(date: String) {
		if (closed || !state.signedIn || state.diaryWeeks.none { it.value == date }) return
		val task = generation
		val revision = ++diaryRevision
		val epoch = observedEpoch
		state = state.copy(selectedDiaryDate = date, diary = DiaryState.Loading, error = null)
		executor.execute {
			if (closed || generation != task || diaryRevision != revision || repository.revocationEpoch() != epoch) return@execute
			currentTaskGeneration = task
			currentDiaryRevision = revision
			currentTaskEpoch = epoch
			repository.refreshDate(date)
		}
	}

	override fun refreshAnnualGrades() {
		if (closed || !state.signedIn) return
		val task = generation
		val revision = ++gradesRevision
		val epoch = observedEpoch
		state = state.copy(annualGrades = null, gradesLastSuccessMillis = null, gradesStaleError = null, gradesLoading = true)
		executor.execute {
			if (closed || generation != task || gradesRevision != revision || repository.revocationEpoch() != epoch) return@execute
			var delivered = false
			try {
				repository.annualGrades { delivery ->
					delivered = true
					handler.post {
						if (!closed && generation == task && gradesRevision == revision && repository.revocationEpoch() == epoch && repository.acceptsAnnualGrades(delivery)) {
							state = state.copy(annualGrades = delivery.result, gradesLastSuccessMillis = delivery.lastSuccessMillis, gradesStaleError = delivery.staleError, gradesLoading = false)
						}
					}
				}
			} catch (_: Exception) { }
			if (!delivered) handler.post {
				if (!closed && generation == task && gradesRevision == revision && repository.revocationEpoch() == epoch) {
					state = state.copy(annualGrades = GatewayResult.Failure(AppError.UNAVAILABLE), gradesLoading = false)
				}
			}
		}
	}
	override fun logout() {
		if (closed || logoutTicket != null) return
		logoutTicket = Any()
		val token = repository.pendingLogoutTicket() ?: repository.cancelInFlightNow()
		logoutTicket = token
		++generation
		state = NativeUiState(busy = true, logoutPending = true)
		logoutExecutor.execute {
			val error = try { repository.logout(token); null } catch (_: Exception) { AppError.UNAVAILABLE }
			handler.post {
				if (logoutTicket !== token) return@post
				logoutTicket = null
				if (!closed) {
					val pending = repository.pendingLogoutTicket() != null
					if (pending || repository.sessionEvent().change != SessionChange.ACTIVATED) {
						state = NativeUiState(logoutPending = pending, error = if (pending) error else null)
					} else if (state.logoutPending) {
						state = NativeUiState()
					}
				}
			}
		}
	}

	override fun close() {
		closed = true
		repository.removeRevocationObserver(observeLogout)
		repository.removeUiRevocationObserver(observeUiLogout)
		generation++
		executor.shutdown()
		logoutExecutor.shutdown()
	}
}

internal fun createNativeController(context: Context): NativeUiController {
	val app = context.applicationContext
	val coordinator = SessionCoordinator(app)
	val cache = EncryptedCache(app, coordinator)
	val store = SessionStore(app, coordinator)
	return NativeController { emit ->
		SchoolRepository(NativeGateway(), NativeIdentityVerifier(), coordinator, EncryptedDiaryCache(cache), System::currentTimeMillis, emit, store, EncryptedDiaryCache(EncryptedCache(app, coordinator, "schedule")), gradesCache = EncryptedDiaryCache(EncryptedCache(app, coordinator, "grades")))
	}
}
