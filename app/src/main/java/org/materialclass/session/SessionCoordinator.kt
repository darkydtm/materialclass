package org.materialclass.session

import java.time.Instant
import org.materialclass.api.SessionController
import org.materialclass.cache.CacheFiles
import org.materialclass.cache.EncryptedCache
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.security.KeyProvider
import org.materialclass.security.SecureCipher

interface SessionTaskRegistry {
	fun cancelAll()
}

class SessionCoordinator(
	private val store: SessionStore,
	private val files: CacheFiles,
	private val keys: KeyProvider,
	private val cipher: SecureCipher,
	private val tasks: SessionTaskRegistry,
) : SessionController {
	private val lock = Any()
	private var active: ActiveSession? = null
	private var nextGeneration = 0L
	private val encryptedCache = EncryptedCache(files, keys, cipher, lock) { active }

	fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession = synchronized(lock) {
		if (active != null) clearLocked()
		val session = ActiveSession(accountId, Generation(nextGeneration++))
		store.save(session, token, expiresAt)
		active = session
		session
	}

	fun restore(now: Instant): Boolean = synchronized(lock) {
		if (active != null) return@synchronized true
		val restored = store.load()
		if (restored == null || !now.isBefore(restored.expiresAt)) {
			clearLocked()
			return@synchronized false
		}
		nextGeneration = maxOf(nextGeneration, restored.session.generation.value + 1)
		active = restored.session
		true
	}

	fun currentSession(): ActiveSession? = synchronized(lock) { active }

	fun tokenFor(session: ActiveSession): String? = synchronized(lock) {
		if (active != session) return@synchronized null
		val restored = store.load() ?: run {
			clearLocked()
			return@synchronized null
		}
		if (restored.session != session) return@synchronized null
		if (!Instant.now().isBefore(restored.expiresAt)) {
			clearLocked()
			return@synchronized null
		}
		restored.token
	}

	fun cache(): EncryptedCache = encryptedCache

	override fun invalidateAndClear() = synchronized(lock) { clearLocked() }

	fun logout() = invalidateAndClear()

	private fun clearLocked() {
		active = null
		tasks.cancelAll()
		store.clear()
		files.cacheFiles().forEach { it.delete() }
		files.temporaryFiles().forEach { it.delete() }
		keys.deleteAll()
	}
}
