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
	private val handler = Handler(Looper.getMainLooper())
	@Volatile private var generation = 0L
	@Volatile private var closed = false
	private var started = false
	private var currentTaskGeneration = 0L
	override var state by mutableStateOf(NativeUiState())
		private set
	private val repository = repositoryFactory { diary ->
		val task = currentTaskGeneration
		post(task) {
			state = if (diary is DiaryState.Error && diary.error == AppError.SESSION_EXPIRED) NativeUiState(error = diary.error)
			else state.copy(diary = diary, error = if (diary is DiaryState.Error) diary.error else state.error)
		}
	}

	private fun post(task: Long, action: () -> Unit) {
		handler.post { if (!closed && generation == task) action() }
	}

	override fun start() {
		if (started || closed) return
		started = true
		val task = ++generation
		state = state.copy(busy = true)
		executor.execute {
			if (closed || generation != task) return@execute
			currentTaskGeneration = task
			val result = try { repository.restore() } catch (_: Exception) { RestoreResult.Retry }
			post(task) {
				state = state.copy(busy = false, signedIn = result is RestoreResult.Restored, error = if (result is RestoreResult.Retry) AppError.UNAVAILABLE else null)
				if (result is RestoreResult.Restored) refresh()
			}
		}
	}

	override fun login(username: String, password: CharArray) {
		if (closed) { password.fill('\u0000'); return }
		val task = ++generation
		state = NativeUiState(busy = true)
		try {
			executor.execute {
				if (closed || generation != task) { password.fill('\u0000'); return@execute }
				currentTaskGeneration = task
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
		state = state.copy(error = null)
		executor.execute {
			if (closed || generation != task) return@execute
			currentTaskGeneration = task
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
		if (closed) return
		val task = ++generation
		state = NativeUiState(busy = true)
		executor.execute {
			currentTaskGeneration = task
			val error = try { repository.logout(); null } catch (_: Exception) { AppError.UNAVAILABLE }
			post(task) { state = NativeUiState(error = error) }
		}
	}

	override fun close() {
		closed = true
		generation++
		executor.shutdown()
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
