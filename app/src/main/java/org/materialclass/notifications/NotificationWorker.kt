package org.materialclass.notifications

import android.content.Context
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.data.SessionAccess

/** Keep worker input independent from server models until notification sources are confirmed. */
data class NotificationSession(
	val activeSession: ActiveSession,
	val tokenPresent: Boolean,
)

data class NotificationItem(
	val category: NotificationCategory,
	val stableId: String,
	val title: String,
	val body: String,
) {
	init {
		require(stableId.isNotBlank()) { "Notification item id must not be blank" }
	}
}

sealed interface CategoryReadResult {
	data class Available(val items: List<NotificationItem>) : CategoryReadResult
	data object Offline : CategoryReadResult
	data object Unsupported : CategoryReadResult
	data object Failed : CategoryReadResult
}

interface NotificationDataSource {
	fun readNewGrades(session: NotificationSession): CategoryReadResult
	fun readHomework(session: NotificationSession): CategoryReadResult
}

/** Do not add transport calls until grades/homework contracts are confirmed. */
object UnsupportedNotificationDataSource : NotificationDataSource {
	override fun readNewGrades(session: NotificationSession): CategoryReadResult = CategoryReadResult.Unsupported

	override fun readHomework(session: NotificationSession): CategoryReadResult = CategoryReadResult.Unsupported
}

enum class NotificationPublishResult { PUBLISHED, PERMISSION_DENIED, FAILED }

interface NotificationPublisher {
	fun publish(item: NotificationItem): NotificationPublishResult
	fun cancelAll()
}

interface NotificationStateStore {
	fun generation(accountId: String): Generation?
	fun reset(accountId: String, generation: Generation)
	fun hasBaseline(accountId: String, generation: Generation, category: NotificationCategory): Boolean
	fun markBaseline(accountId: String, generation: Generation, category: NotificationCategory)
	fun hasSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String): Boolean
	fun markSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String)
	fun clear()
}

class InMemoryNotificationStateStore : NotificationStateStore {
	private data class Key(val accountId: String, val generation: Generation, val category: NotificationCategory)
	private var currentGeneration: MutableMap<String, Generation> = mutableMapOf()
	private val baselines = mutableSetOf<Key>()
	private val seen = mutableSetOf<Pair<Key, String>>()

	override fun generation(accountId: String): Generation = currentGeneration[accountId] ?: Generation(0)

	override fun reset(accountId: String, generation: Generation) {
		currentGeneration[accountId] = generation
		baselines.removeAll { it.accountId == accountId && it.generation != generation }
		seen.removeAll { it.first.accountId == accountId && it.first.generation != generation }
	}

	override fun hasBaseline(accountId: String, generation: Generation, category: NotificationCategory): Boolean =
		Key(accountId, generation, category) in baselines

	override fun markBaseline(accountId: String, generation: Generation, category: NotificationCategory) {
		baselines += Key(accountId, generation, category)
	}

	override fun hasSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String): Boolean =
		(Key(accountId, generation, category) to stableId) in seen

	override fun markSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String) {
		seen += Key(accountId, generation, category) to stableId
	}

	override fun clear() {
		currentGeneration.clear()
		baselines.clear()
		seen.clear()
	}
}

class SharedPreferencesNotificationStateStore(context: Context) : NotificationStateStore {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	override fun generation(accountId: String): Generation = Generation(preferences.getLong(generationKey(accountId), 0L))

	override fun reset(accountId: String, generation: Generation) {
		val editor = preferences.edit().putLong(generationKey(accountId), generation.value)
		NotificationCategory.entries.forEach { category -> editor.remove(baselineKey(accountId, generation, category)) }
		editor.apply()
	}

	override fun hasBaseline(accountId: String, generation: Generation, category: NotificationCategory): Boolean =
		preferences.getBoolean(baselineKey(accountId, generation, category), false)

	override fun markBaseline(accountId: String, generation: Generation, category: NotificationCategory) {
		preferences.edit().putBoolean(baselineKey(accountId, generation, category), true).apply()
	}

	override fun hasSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String): Boolean =
		preferences.getStringSet(seenKey(accountId, generation, category), emptySet()).orEmpty().contains(stableId)

	override fun markSeen(accountId: String, generation: Generation, category: NotificationCategory, stableId: String) {
		val key = seenKey(accountId, generation, category)
		val values = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
		values += stableId
		preferences.edit().putStringSet(key, values).apply()
	}

	override fun clear() {
		preferences.edit().clear().apply()
	}

	private fun generationKey(accountId: String): String = "generation:$accountId"
	private fun baselineKey(accountId: String, generation: Generation, category: NotificationCategory): String = "baseline:$accountId:${generation.value}:${category.name}"
	private fun seenKey(accountId: String, generation: Generation, category: NotificationCategory): String = "seen:$accountId:${generation.value}:${category.name}"

	private companion object {
		const val PREFERENCES_NAME = "notification-state"
	}
}

enum class NotificationRunStatus {
	BASELINE,
	PROCESSED,
	OFFLINE,
	FAILED,
	PERMISSION_DENIED,
	NO_SESSION,
	NO_TOKEN,
	STALE_GENERATION,
	UNSUPPORTED,
}

data class NotificationRunResult(
	val status: NotificationRunStatus,
	val publishedCount: Int = 0,
)

class NotificationProcessor(
	private val state: NotificationStateStore,
	private val publisher: NotificationPublisher,
	private val sessions: SessionAccess,
) {
	fun clearState() {
		sessions.withSessionLock { state.clear() }
	}

	fun process(
		session: NotificationSession,
		preferences: NotificationPreferences,
		permissionGranted: Boolean,
		reads: Map<NotificationCategory, CategoryReadResult>,
		currentPreferences: () -> NotificationPreferences = { preferences },
	): NotificationRunResult = sessions.withSessionLock {
		if (sessions.currentSession() != session.activeSession) return@withSessionLock NotificationRunResult(NotificationRunStatus.STALE_GENERATION)
		val effectivePreferences = currentPreferences()
		val enabledReads = reads.filterKeys { preferences.isEnabled(it) && effectivePreferences.isEnabled(it) }
		if (enabledReads.values.any { it is CategoryReadResult.Failed }) return@withSessionLock NotificationRunResult(NotificationRunStatus.FAILED)
		if (enabledReads.values.any { it is CategoryReadResult.Offline }) return@withSessionLock NotificationRunResult(NotificationRunStatus.OFFLINE)
		if (enabledReads.values.any { it is CategoryReadResult.Unsupported }) return@withSessionLock NotificationRunResult(NotificationRunStatus.UNSUPPORTED)
		val accountId = session.activeSession.accountId.value
		val generation = session.activeSession.generation
		if (state.generation(accountId) != generation) state.reset(accountId, generation)
		val pendingBaselines = mutableMapOf<NotificationCategory, List<NotificationItem>>()
		var baselineCreated = false
		var published = 0
		var sawOffline = false
		var sawFailed = false
		var sawUnsupported = false
		var sawAvailable = false
		var denied = false
		for ((category, result) in enabledReads) {
			when (result) {
				is CategoryReadResult.Available -> {
					sawAvailable = true
					if (!state.hasBaseline(accountId, generation, category)) {
						pendingBaselines[category] = result.items
						continue
					}
					result.items.forEach { item ->
						if (state.hasSeen(accountId, generation, category, item.stableId)) return@forEach
						if (!permissionGranted) {
							denied = true
						} else when (try { publisher.publish(item) } catch (_: Exception) { NotificationPublishResult.FAILED }) {
							NotificationPublishResult.PUBLISHED -> {
								state.markSeen(accountId, generation, category, item.stableId)
								published++
							}
							NotificationPublishResult.PERMISSION_DENIED -> denied = true
							NotificationPublishResult.FAILED -> sawFailed = true
						}
					}
				}
				CategoryReadResult.Offline -> sawOffline = true
				CategoryReadResult.Unsupported -> sawUnsupported = true
				CategoryReadResult.Failed -> sawFailed = true
			}
		}
		if (!sawFailed && !denied) {
			for ((category, items) in pendingBaselines) {
				items.forEach { state.markSeen(accountId, generation, category, it.stableId) }
				state.markBaseline(accountId, generation, category)
				baselineCreated = true
			}
		}
		when {
			sawFailed -> NotificationRunResult(NotificationRunStatus.FAILED, published)
			sawOffline -> NotificationRunResult(NotificationRunStatus.OFFLINE, published)
			baselineCreated -> NotificationRunResult(NotificationRunStatus.BASELINE, published)
			(denied || !permissionGranted) && sawAvailable -> NotificationRunResult(NotificationRunStatus.PERMISSION_DENIED, published)
			sawUnsupported && !sawAvailable -> NotificationRunResult(NotificationRunStatus.UNSUPPORTED, published)
			else -> NotificationRunResult(NotificationRunStatus.PROCESSED, published)
		}
	}
}

class NotificationWorkerRunner(
	private val source: NotificationDataSource,
	private val processor: NotificationProcessor,
	private val isCurrentGeneration: (ActiveSession) -> Boolean,
) {
	fun run(session: NotificationSession?, preferences: NotificationPreferences, permissionGranted: Boolean, currentPreferences: () -> NotificationPreferences = { preferences }): NotificationRunResult {
		if (session == null) return NotificationRunResult(NotificationRunStatus.NO_SESSION)
		if (!session.tokenPresent) return NotificationRunResult(NotificationRunStatus.NO_TOKEN)
		if (!preferences.newGradeEnabled && !preferences.homeworkEnabled) return NotificationRunResult(NotificationRunStatus.PROCESSED)
		if (!isCurrentGeneration(session.activeSession)) return NotificationRunResult(NotificationRunStatus.STALE_GENERATION)
		val reads = buildMap {
			if (preferences.newGradeEnabled) put(NotificationCategory.NEW_GRADE, source.readNewGrades(session))
			if (preferences.homeworkEnabled) put(NotificationCategory.HOMEWORK, source.readHomework(session))
		}
		if (!isCurrentGeneration(session.activeSession)) return NotificationRunResult(NotificationRunStatus.STALE_GENERATION)
		return processor.process(session, preferences, permissionGranted, reads, currentPreferences)
	}
}

fun interface NotificationWorkerRuntime {
	fun run(): NotificationRunResult
}

object NotificationWorkerRuntimeRegistry {
	@Volatile
	var runtime: NotificationWorkerRuntime? = null
}
