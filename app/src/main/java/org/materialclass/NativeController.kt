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
import org.materialclass.data.DiaryState
import org.materialclass.data.EncryptedDiaryCache
import org.materialclass.data.NativeIdentityVerifier
import org.materialclass.data.SchoolRepository
import org.materialclass.session.RestoreResult
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import java.util.concurrent.Executors

internal data class NativeUiState(
	val signedIn: Boolean = false,
	val busy: Boolean = false,
	val logoutPending: Boolean = false,
	val diary: DiaryState = DiaryState.Cleared,
	val schedule: Schedule? = null,
	val error: AppError? = null,
)

internal interface NativeUiController {
	val state: NativeUiState
	fun start()
	fun login(username: String, password: CharArray)
	fun refresh()
	fun logout()
	fun close()
}

internal class NativeController(private val repositoryFactory: ((DiaryState) -> Unit) -> SchoolRepository) : NativeUiController {
	private val executor = Executors.newSingleThreadExecutor()
	private val logoutExecutor = Executors.newSingleThreadExecutor()
	private var logoutPending = false
	private val handler = Handler(Looper.getMainLooper())
	@Volatile private var observedEpoch = 0L
	private val observeLogout: (Long) -> Unit = { epoch ->
		val apply = {
			if (!closed && epoch == repository.revocationEpoch()) {
				if (epoch != observedEpoch) {
					observedEpoch = epoch
					generation++
					state = NativeUiState(logoutPending = repository.pendingLogoutTicket() != null)
				} else if (state.logoutPending && !logoutPending && repository.pendingLogoutTicket() == null) {
					state = NativeUiState()
				}
			}
		}
		if (Looper.myLooper() == Looper.getMainLooper()) apply() else handler.post { apply() }
	}
	@Volatile private var generation = 0L
	@Volatile private var closed = false
	private var started = false
	private var currentTaskGeneration = 0L
	@Volatile private var currentTaskEpoch = 0L
	override var state by mutableStateOf(NativeUiState())
		private set
	private val repository = repositoryFactory { diary ->
		val task = currentTaskGeneration
		post(task) {
			state = if (diary is DiaryState.Error && diary.error == AppError.SESSION_EXPIRED) NativeUiState(error = diary.error)
			else state.copy(diary = diary, error = if (diary is DiaryState.Error) diary.error else state.error)
		}
	}

	init {
		val snapshot = repository.observeRevocations(observeLogout)
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
		if (repository.pendingLogoutTicket() != null) {
			state = NativeUiState(logoutPending = true)
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
			post(task) {
				state = state.copy(busy = false, signedIn = result is RestoreResult.Restored, error = if (result is RestoreResult.Retry) AppError.UNAVAILABLE else null)
				if (result is RestoreResult.Restored) refresh()
			}
		}
	}

	override fun login(username: String, password: CharArray) {
		if (closed || logoutPending || repository.pendingLogoutTicket() != null) { password.fill('\u0000'); return }
		val task = ++generation
		val epoch = observedEpoch
		state = NativeUiState(busy = true)
		try {
			executor.execute {
				if (closed || generation != task) { password.fill('\u0000'); return@execute }
				currentTaskGeneration = task
			currentTaskEpoch = epoch
				val result = try { repository.login(username, password) } catch (_: Exception) { GatewayResult.Failure(AppError.UNAVAILABLE) } finally { password.fill('\u0000') }
				post(task) {
					state = state.copy(busy = false, signedIn = result is GatewayResult.Success, error = (result as? GatewayResult.Failure)?.error)
					if (result is GatewayResult.Success) refresh()
				}
			}
		} catch (_: java.util.concurrent.RejectedExecutionException) { password.fill('\u0000'); state = state.copy(busy = false, error = AppError.UNAVAILABLE) }
	}

	override fun refresh() {
		if (closed || !state.signedIn) return
		val task = ++generation
		val epoch = observedEpoch
		state = state.copy(error = null)
		executor.execute {
			if (closed || generation != task) return@execute
			currentTaskGeneration = task
			currentTaskEpoch = epoch
			try {
				repository.refreshCurrent()
				repository.currentWeek { week ->
					when (week) {
						is GatewayResult.Success -> repository.schedule(week.value) { result ->
							post(task) { state = when (result) {
								is GatewayResult.Success -> state.copy(schedule = result.value)
								is GatewayResult.Failure -> state.copy(schedule = null, error = result.error)
							} }
						}
						is GatewayResult.Failure -> post(task) { state = state.copy(schedule = null, error = week.error) }
					}
				}
			} catch (_: Exception) { post(task) { if (state.error != AppError.SESSION_EXPIRED) state = state.copy(error = AppError.UNAVAILABLE) } }
		}
	}

	override fun logout() {
		if (closed || logoutPending) return
		logoutPending = true
		val token = repository.pendingLogoutTicket() ?: repository.cancelInFlightNow()
		val task = ++generation
		val epoch = repository.revocationEpoch()
		state = NativeUiState(busy = true, logoutPending = true)
		logoutExecutor.execute {
			val error = try { repository.logout(token); null } catch (_: Exception) { AppError.UNAVAILABLE }
			handler.post {
				logoutPending = false
				if (!closed && generation == task && repository.revocationEpoch() == epoch) {
					val pending = repository.pendingLogoutTicket() != null
					state = NativeUiState(logoutPending = pending, error = error)
				}
			}
		}
	}

	override fun close() {
		closed = true
		repository.removeRevocationObserver(observeLogout)
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
		SchoolRepository(NativeGateway(), NativeIdentityVerifier(), coordinator, EncryptedDiaryCache(cache), System::currentTimeMillis, emit, store)
	}
}
