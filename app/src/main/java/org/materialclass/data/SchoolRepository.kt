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
	fun loadHome() { refreshHome() }
	fun loadDiary(range: DateRange) { refreshDiary(range) }
	fun loadSchedule(range: DateRange) { refreshSchedule(range) }
	fun loadGrades(period: String) { refreshGrades(period) }
	fun loadHomeworks() { refreshHomeworks() }
	fun loadJournal(range: DateRange) { refreshJournal(range) }
	fun loadProfile() { refreshProfile() }
	fun gradeComparison(uuid: String, type: String): GatewayReadResult<GradeComparison> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	fun quarterRange(period: String): DateRange? = null
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
	fun gradeComparison(context: ReadContext, uuid: String, type: String): GatewayReadResult<GradeComparison> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	fun periods(context: ReadContext): GatewayReadResult<PeriodsModel> = GatewayReadResult.Failure(AppError.UnsupportedContract)
	fun profile(): GatewayReadResult<ProfileModel>
	fun logout(): GatewayReadResult<Unit> = GatewayReadResult.Success(Unit)
	fun refreshSession(session: ActiveSession): Boolean = false
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
	private val revisions = mutableMapOf<String, Long>()
	private val requestedSlices = mutableSetOf<Pair<ActiveSession, String>>()
	private val events = RepositoryEvents()
	fun events(): RepositoryEvents = events
	override fun observeHome() = observation<HomeModel>(HOME)
	override fun observeDiary(range: DateRange) = observation<DiaryModel>(diaryKey(range))
	override fun observeSchedule(range: DateRange) = observation<ScheduleModel>(scheduleKey(range))
	override fun observeGrades(period: String) = observation<GradesModel>(gradesKey(period))
	override fun observeHomeworks() = observation<HomeworksModel>(HOMEWORKS)
	override fun observeJournal(range: DateRange) = observation<JournalGradesModel>(journalKey(range))
	override fun observeProfile() = observation<ProfileModel>(PROFILE)
	override fun gradeComparison(uuid: String, type: String): GatewayReadResult<GradeComparison> =
		authenticatedRead("comparison") { gateway.gradeComparison(it, uuid, type) }

	override fun quarterRange(period: String): DateRange? = org.materialclass.notifications.SelectedPeriodRange.resolve(
		period, authenticatedRead("periods") { gateway.periods(it) },
	)

	private fun <T> authenticatedRead(key: String, call: (ReadContext) -> GatewayReadResult<T>): GatewayReadResult<T> {
		val session = sessions.currentSession() ?: return GatewayReadResult.Failure(AppError.LoginRequired)
		var token = sessions.tokenFor(session)
		if (token == null && gateway.refreshSession(session)) token = sessions.tokenFor(session)
		if (token == null || sessions.currentSession() != session) return GatewayReadResult.Failure(AppError.LoginRequired)
		var result = call(context(token, key))
		if (result is GatewayReadResult.Failure && result.error == AppError.Unauthorized && sessions.currentSession() == session && gateway.refreshSession(session)) {
			val renewed = sessions.tokenFor(session)
			if (renewed != null && sessions.currentSession() == session) result = call(context(renewed, key))
		}
		return sessions.withSessionLock { if (sessions.currentSession() == session) result else GatewayReadResult.Failure(AppError.LoginRequired) }
	}

	override fun refreshHome() { refresh(HOME) { gateway.home(it) } }
	override fun refreshDiary(range: DateRange) { refresh(diaryKey(range)) { gateway.diary(it) } }
	override fun refreshSchedule(range: DateRange) { refresh(scheduleKey(range)) { gateway.schedule(it) } }
	fun refreshScheduleFor(session: ActiveSession, range: DateRange) = refresh(scheduleKey(range), session) { gateway.schedule(it) }
	override fun refreshGrades(period: String) {
		refresh(gradesKey(period)) { gateway.grades(it) }
	}
	fun refreshGradesFor(session: ActiveSession, period: String) = refresh(gradesKey(period), session) { gateway.grades(it) }
	override fun refreshHomeworks() { refresh(HOMEWORKS) { gateway.homeworks(it) } }
	override fun refreshJournal(range: DateRange) { refresh(journalKey(range)) { gateway.journalGrades(it) } }
	override fun refreshProfile() { refresh(PROFILE) { gateway.profile() } }
	override fun loadHome() { load(HOME) { gateway.home(it) } }
	override fun loadDiary(range: DateRange) { load(diaryKey(range)) { gateway.diary(it) } }
	override fun loadSchedule(range: DateRange) { load(scheduleKey(range)) { gateway.schedule(it) } }
	override fun loadGrades(period: String) { load(gradesKey(period)) { gateway.grades(it) } }
	override fun loadHomeworks() { load(HOMEWORKS) { gateway.homeworks(it) } }
	override fun loadJournal(range: DateRange) { load(journalKey(range)) { gateway.journalGrades(it) } }
	override fun loadProfile() { load(PROFILE) { gateway.profile() } }
	override fun logout() {
		if (gateway.logout() !is GatewayReadResult.Success) return
		sessions.withSessionLock { synchronized(lock) { states.clear(); requestedSlices.clear(); observations.values.forEach { it.update(DataState.Loading) } } }
	}

	private fun <T> load(key: String, call: (ReadContext) -> GatewayReadResult<T>) {
		val session = sessions.currentSession() ?: run { refresh(key, call = call); return }
		val existing = synchronized(lock) { states[key]?.takeIf { snapshotFor(it, session) != null } }
		val needsCache = existing == null && synchronized(lock) { (session to key) !in requestedSlices }
		val restored = if (needsCache) readSlice<T>(key, session) else null
		val shouldLoad = sessions.withSessionLock {
			if (sessions.currentSession() != session) return@withSessionLock false
			synchronized(lock) {
				if ((session to key) in requestedSlices) return@synchronized false
				val state = states[key]?.takeIf { snapshotFor(it, session) != null } ?: restored?.also {
					states[key] = it
					update(key, it)
				}
				val snapshot = snapshotFor(state, session)
				if (snapshot != null) false
				else {
					if (state != null) {
						states.remove(key)
						update(key, DataState.Loading)
					}
					requestedSlices.add(session to key)
				}
			}
		}
		if (shouldLoad) refresh(key, session, call)
	}

	private fun snapshotFor(state: DataState<*>?, session: ActiveSession): Snapshot<*>? {
		val snapshot = when (state) {
			is DataState.Ready<*> -> state.snapshot
			is DataState.Stale<*> -> state.snapshot
			is DataState.Error<*> -> state.snapshot
			is DataState.Offline<*> -> state.snapshot
			else -> null
		}
		return snapshot?.takeIf { it.accountId == session.accountId && it.generation == session.generation }
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> observation(key: String): RepositoryObservation<T> = synchronized(lock) {
		@Suppress("UNCHECKED_CAST")
		(observations[key] as RepositoryObservation<T>?)
			?: RepositoryObservation(states[key] as DataState<T>? ?: DataState.Loading).also {
				observations[key] = it
			}
	}

	private fun <T> refresh(key: String, expectedSession: ActiveSession? = null, call: (ReadContext) -> GatewayReadResult<T>): Boolean {
		if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.REPOSITORY, DiaryOutcome.START)
		val session = sessions.currentSession() ?: run {
			if (expectedSession == null) publishFailureIfCurrent(key, null, AppError.LoginRequired)
			return false
		}
		if (expectedSession != null && session != expectedSession) return false
		val revision = synchronized(lock) { (revisions[key] ?: 0L).plus(1).also { revisions[key] = it } }
		val needsRestore = synchronized(lock) { states[key] == null || snapshotFor(states[key], session) == null }
		if (needsRestore) {
			val cached = readSlice<T>(key, session)
			if (cached != null) sessions.withSessionLock {
				if (sessions.currentSession() == session) synchronized(lock) {
					if (states[key] == null || snapshotFor(states[key], session) == null) { states[key] = cached; update(key, cached) }
				}
			}
		}
		val token = sessions.tokenFor(session) ?: run {
			if (sessions.currentSession() == session) {
				val refreshed = gateway.refreshSession(session)
				if (sessions.currentSession() != session) return false
				if (refreshed) {
					val refreshedToken = sessions.tokenFor(session) ?: run {
						publishFailureIfCurrent(key, session, AppError.LoginRequired)
						return false
					}
					if (sessions.currentSession() != session) return false
					return refreshWithToken(key, session, revision, call, refreshedToken)
				}
				publishFailureIfCurrent(key, session, AppError.LoginRequired)
			}
			return false
		}
		return refreshWithToken(key, session, revision, call, token)
	}

	private fun <T> refreshWithToken(
		key: String,
		session: ActiveSession,
		revision: Long,
		call: (ReadContext) -> GatewayReadResult<T>,
		token: String,
	): Boolean {
		var result = try { call(context(token, key)) } catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw exception
		} catch (exception: java.util.concurrent.CancellationException) {
			throw exception
		} catch (exception: Exception) {
			if (key.startsWith("grades:")) DiaryDiagnostics.record(DiaryStage.REPOSITORY, DiaryOutcome.FAILURE, error = AppError.Network, exception = exception)
			GatewayReadResult.Failure(AppError.Network)
		}
		if (result is GatewayReadResult.Failure && result.error == AppError.Unauthorized && sessions.currentSession() == session) {
			val refreshed = gateway.refreshSession(session)
			if (refreshed && sessions.currentSession() == session) {
				val refreshedToken = sessions.tokenFor(session)
				if (refreshedToken != null) {
					val retriedResult = try { call(context(refreshedToken, key)) } catch (exception: InterruptedException) {
						Thread.currentThread().interrupt()
						throw exception
					} catch (exception: java.util.concurrent.CancellationException) {
						throw exception
					} catch (exception: Exception) {
						GatewayReadResult.Failure(AppError.Network)
					}
					result = if (sessions.currentSession() == session) retriedResult else GatewayReadResult.Failure(AppError.LoginRequired)
				}
			}
		}
		if (sessions.currentSession() != session || Thread.currentThread().isInterrupted) return false
		return sessions.withSessionLock {
			if (sessions.currentSession() != session) false
			else synchronized(lock) {
				if (revisions[key] != revision) false else handleResult(key, session, result)
			}
		}
	}

	private fun publishFailureIfCurrent(key: String, session: ActiveSession?, error: AppError) = sessions.withSessionLock {
		if (sessions.currentSession() == session) synchronized(lock) { handleFailure(key, error) }
	}
	private fun <T> handleResult(key: String, session: ActiveSession, result: GatewayReadResult<T>): Boolean = when (result) {
		is GatewayReadResult.Success -> commit(key, session, result.value)
		GatewayReadResult.Empty -> {
			if (writeSlices(session, key, CachedSlice(EMPTY_PAYLOAD, now()))) {
				states[key] = DataState.Empty
				update(key, DataState.Empty)
				true
			} else {
				handleFailure(key, AppError.Network)
				false
			}
		}
		is GatewayReadResult.Failure -> { handleFailure(key, result.error); false }
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

	private fun <T> commit(key: String, session: ActiveSession, value: T): Boolean {
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
			return false
		}
		states[key] = DataState.Ready(snapshot)
		update(key, DataState.Ready(snapshot))
		return true
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> restoreSlice(key: String, session: ActiveSession) {
		readSlice<T>(key, session)?.let { states[key] = it; update(key, it) }
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> readSlice(key: String, session: ActiveSession): DataState<T>? {
		val slice = (store.read(session) as? SnapshotStoreRead.Hit)?.slices?.get(key) ?: return null
		if (slice.payload.contentEquals(EMPTY_PAYLOAD)) return DataState.Empty
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
		}.getOrNull() ?: return null
		return DataState.Stale(Snapshot(session.accountId, session.generation, value, slice.capturedAt))
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
