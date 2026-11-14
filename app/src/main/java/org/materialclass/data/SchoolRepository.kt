package org.materialclass.data

import java.time.Instant
import org.materialclass.diagnostics.DiaryDiagnostics
import org.materialclass.diagnostics.DiaryOutcome
import org.materialclass.diagnostics.DiaryStage
import org.materialclass.api.AppError
import org.materialclass.api.GatewayReadResult
import org.materialclass.api.ReadContext

interface SchoolRepository {
	fun observeHome(): RepositoryObservation<HomeModel>
	fun observeDiary(range: DateRange): RepositoryObservation<DiaryModel>
	fun observeSchedule(range: DateRange): RepositoryObservation<ScheduleModel>
	fun observeGrades(period: String): RepositoryObservation<GradesModel>
	fun observeHomeworks(): RepositoryObservation<HomeworksModel>
	fun observeJournal(range: DateRange): RepositoryObservation<JournalGradesModel>
	fun observeProfile(): RepositoryObservation<ProfileModel>
	fun refreshHome()
	fun refreshDiary(range: DateRange)
	fun refreshSchedule(range: DateRange)
	fun refreshGrades(period: String)
	fun refreshHomeworks()
	fun refreshJournal(range: DateRange)
	fun refreshProfile()
	fun logout()
}

interface RepositoryGateway {
	fun home(context: ReadContext): GatewayReadResult<HomeModel>
	fun diary(context: ReadContext): GatewayReadResult<DiaryModel>
	fun schedule(context: ReadContext): GatewayReadResult<ScheduleModel>
	fun grades(context: ReadContext): GatewayReadResult<GradesModel>
	fun homeworks(context: ReadContext): GatewayReadResult<HomeworksModel> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	fun journalGrades(context: ReadContext): GatewayReadResult<JournalGradesModel> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	fun profile(): GatewayReadResult<ProfileModel>
	fun logout(): GatewayReadResult<Unit> = GatewayReadResult.Success(Unit)
}

interface SessionAccess {
	fun currentSession(): ActiveSession?
	fun tokenFor(session: ActiveSession): String?
	fun <T> withSessionLock(block: () -> T): T
}

interface SnapshotStore {
	fun read(session: ActiveSession): SnapshotStoreRead
	fun write(session: ActiveSession, slices: Map<String, CachedSlice>): Boolean
}

sealed interface SnapshotStoreRead {
	data class Hit(val slices: Map<String, CachedSlice>) : SnapshotStoreRead
	data object Missing : SnapshotStoreRead
	data object Unavailable : SnapshotStoreRead
}

data class CachedSlice(val payload: ByteArray, val capturedAt: Instant) {
	override fun equals(other: Any?): Boolean = other is CachedSlice && payload.contentEquals(other.payload) && capturedAt == other.capturedAt
	override fun hashCode(): Int = 31 * payload.contentHashCode() + capturedAt.hashCode()
}

class RepositoryObservation<T>(initial: DataState<T>) {
	@Volatile var current: DataState<T> = initial
		private set
	private val listeners = mutableListOf<(DataState<T>) -> Unit>()
	fun observe(listener: (DataState<T>) -> Unit) { synchronized(listeners) { listeners += listener }; listener(current) }
	internal fun update(state: DataState<T>) { current = state; synchronized(listeners) { listeners.toList() }.forEach { it(state) } }
}

sealed interface RepositoryEvent {
	data class Toast(val error: AppError) : RepositoryEvent
	data class RetryAvailable(val slice: String) : RepositoryEvent
}

class RepositoryEvents {
	private val listeners = mutableListOf<(RepositoryEvent) -> Unit>()
	fun observe(listener: (RepositoryEvent) -> Unit) { synchronized(listeners) { listeners += listener } }
	fun emit(event: RepositoryEvent) { synchronized(listeners) { listeners.toList() }.forEach { it(event) } }
}

class SchoolRepositoryImpl(
	private val gateway: RepositoryGateway,
	private val sessions: SessionAccess,
	private val store: SnapshotStore,
	private val now: () -> Instant = { Instant.now() },
	private val context: (String, String) -> ReadContext = { token, _ -> ReadContext(token) },
) : SchoolRepository {
	private val lock = Any()
	private val states = mutableMapOf<String, DataState<*>>()
	private val observations = mutableMapOf<String, RepositoryObservation<*>>()
	private val events = RepositoryEvents()
	fun events(): RepositoryEvents = events
	override fun observeHome() = observation<HomeModel>(HOME)
	override fun observeDiary(range: DateRange) = observation<DiaryModel>(diaryKey(range))
	override fun observeSchedule(range: DateRange) = observation<ScheduleModel>(scheduleKey(range))
	override fun observeGrades(period: String) = observation<GradesModel>(gradesKey(period))
	override fun observeHomeworks() = observation<HomeworksModel>(HOMEWORKS)
	override fun observeJournal(range: DateRange) = observation<JournalGradesModel>(journalKey(range))
	override fun observeProfile() = observation<ProfileModel>(PROFILE)

	override fun refreshHome() = refresh(HOME) { gateway.home(it) }
	override fun refreshDiary(range: DateRange) = refresh(diaryKey(range)) { gateway.diary(it) }
	override fun refreshSchedule(range: DateRange) = refresh(scheduleKey(range)) { gateway.schedule(it) }
	fun refreshScheduleFor(session: ActiveSession, range: DateRange) = refresh(scheduleKey(range), session) { gateway.schedule(it) }
	override fun refreshGrades(period: String) = refresh(gradesKey(period)) { gateway.grades(it) }
	override fun refreshHomeworks() = refresh(HOMEWORKS) { gateway.homeworks(it) }
	override fun refreshJournal(range: DateRange) = refresh(journalKey(range)) { gateway.journalGrades(it) }
	override fun refreshProfile() = refresh(PROFILE) { gateway.profile() }
	override fun logout() {
		if (gateway.logout() !is GatewayReadResult.Success) return
		sessions.withSessionLock { synchronized(lock) { states.clear(); observations.values.forEach { it.update(DataState.Loading) } } }
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> observation(key: String): RepositoryObservation<T> = synchronized(lock) {
		@Suppress("UNCHECKED_CAST")
		(observations[key] as RepositoryObservation<T>?)
			?: RepositoryObservation(states[key] as DataState<T>? ?: DataState.Loading).also {
				observations[key] = it
			}
	}

	private fun <T> refresh(key: String, expectedSession: ActiveSession? = null, call: (ReadContext) -> GatewayReadResult<T>) {
		if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.REPOSITORY, DiaryOutcome.START)
		val session = sessions.currentSession() ?: return if (expectedSession == null) publishFailureIfCurrent(key, null, AppError.LoginRequired) else Unit
		if (expectedSession != null && session != expectedSession) return
		sessions.withSessionLock {
			synchronized(lock) {
				if (sessions.currentSession() == session && states[key] == null) restoreSlice<T>(key, session)
			}
		}
		val token = sessions.tokenFor(session) ?: return if (expectedSession == null) publishFailureIfCurrent(key, session, AppError.LoginRequired) else Unit
		val result = try { call(context(token, key)) } catch (exception: Exception) {
			if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.REPOSITORY, DiaryOutcome.FAILURE, error = AppError.Network, exception = exception)
			GatewayReadResult.Failure(AppError.Network)
		}
		sessions.withSessionLock {
			synchronized(lock) {
				if (sessions.currentSession() != session) return@synchronized
				handleResult(key, session, result)
			}
		}
	}

	private fun publishFailureIfCurrent(key: String, session: ActiveSession?, error: AppError) = sessions.withSessionLock {
		if (sessions.currentSession() == session) synchronized(lock) { handleFailure(key, error) }
	}
	private fun <T> handleResult(key: String, session: ActiveSession, result: GatewayReadResult<T>) {
		when (result) {
			is GatewayReadResult.Success -> commit(key, session, result.value)
			GatewayReadResult.Empty -> {
				states[key] = DataState.Empty
				update(key, DataState.Empty)
				writeSlices(session, key, CachedSlice(EMPTY_PAYLOAD, now()))
			}
			is GatewayReadResult.Failure -> handleFailure(key, result.error)
		}
	}

	private fun handleFailure(key: String, error: AppError) {
		if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.REPOSITORY, DiaryOutcome.FAILURE, error = error)
		val previous = states[key]
		val snapshot = when (previous) {
			is DataState.Ready<*> -> previous.snapshot
			is DataState.Stale<*> -> previous.snapshot
			is DataState.Error<*> -> previous.snapshot
			else -> null
		}
		val next = if (snapshot != null) {
			DataState.Error(error, snapshot)
		} else if (error == AppError.Offline) {
			DataState.Offline<Any>()
		} else {
			DataState.Error(error)
		}
		states[key] = next
		@Suppress("UNCHECKED_CAST")
		update(key, next as DataState<Any>)
		emitFailure(key, error)
	}

	private fun <T> commit(key: String, session: ActiveSession, value: T) {
		val snapshot = Snapshot(session.accountId, session.generation, value, now())
		val payload = runCatching {
			java.io.ByteArrayOutputStream().also { output -> java.io.ObjectOutputStream(output).use { it.writeObject(value) } }.toByteArray()
		}.onSuccess {
			if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.SERIALIZE, DiaryOutcome.SUCCESS)
		}.onFailure { exception ->
			if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.SERIALIZE, DiaryOutcome.FAILURE, exception = exception)
		}.getOrNull()
		if (payload == null || !writeSlices(session, key, CachedSlice(payload, snapshot.capturedAt))) {
			handleFailure(key, AppError.Network)
			return
		}
		states[key] = DataState.Ready(snapshot)
		update(key, DataState.Ready(snapshot))
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> restoreSlice(key: String, session: ActiveSession) {
		val slice = (store.read(session) as? SnapshotStoreRead.Hit)?.slices?.get(key) ?: return
		if (slice.payload.contentEquals(EMPTY_PAYLOAD)) {
			states[key] = DataState.Empty
			update(key, DataState.Empty)
			return
		}
		val value = runCatching {
			java.io.ObjectInputStream(slice.payload.inputStream()).use { input ->
				val decoded = input.readObject()
				val valid = when {
					key == HOME -> decoded is HomeModel
					key == PROFILE -> decoded is ProfileModel
					key == HOMEWORKS -> decoded is HomeworksModel
					key.startsWith("diary:") -> decoded is DiaryModel
					key.startsWith("schedule:") -> decoded is ScheduleModel
					key.startsWith("grades:") -> decoded is GradesModel
					key.startsWith("journal:") -> decoded is JournalGradesModel
					else -> false
				}
				if (valid) decoded as T else null
			}
		}.getOrNull() ?: return
		val state = DataState.Stale(Snapshot(session.accountId, session.generation, value, slice.capturedAt))
		states[key] = state
		update(key, state)
	}

	private fun writeSlices(session: ActiveSession, key: String, slice: CachedSlice): Boolean {
		val existing = (store.read(session) as? SnapshotStoreRead.Hit)?.slices.orEmpty()
		val saved = store.write(session, existing + (key to slice))
		if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.SNAPSHOT, if (saved) DiaryOutcome.SUCCESS else DiaryOutcome.FAILURE)
		return saved
	}

	private fun emitFailure(key: String, error: AppError) {
		events.emit(RepositoryEvent.Toast(error))
		events.emit(RepositoryEvent.RetryAvailable(key))
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> update(key: String, state: DataState<T>) {
		(observations[key] as RepositoryObservation<T>?)?.update(state)
	}
	private fun diaryKey(range: DateRange) = "diary:${range.start}:${range.endInclusive}"
	private fun scheduleKey(range: DateRange) = "schedule:${range.start}:${range.endInclusive}"
	private fun gradesKey(period: String) = "grades:$period"
	private fun journalKey(range: DateRange) = "journal:${range.start}:${range.endInclusive}"
	private companion object { const val HOME = "home"; const val PROFILE = "profile"; const val HOMEWORKS = "homeworks"; val EMPTY_PAYLOAD = byteArrayOf(0) }
}
