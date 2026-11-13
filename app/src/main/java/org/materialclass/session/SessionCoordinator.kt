package org.materialclass.session

import java.time.Instant
import org.materialclass.api.SessionController
import org.materialclass.cache.CacheFiles
import org.materialclass.cache.EncryptedCache
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.data.SessionAccess
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
) : SessionController, SessionAccess {
	private val lock = Any()
	private var active: ActiveSession? = null
	private var nextGeneration = 0L
	private var refreshToken: String? = null
	private val encryptedCache = EncryptedCache(files, keys, cipher, lock) { active }

	override fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession = synchronized(lock) {
		if (active != null) clearLocked()
		val session = ActiveSession(accountId, Generation(nextGeneration++))
		store.save(session, token, expiresAt)
		active = session
		refreshToken = null
		session
	}

	override fun setRefreshToken(session: ActiveSession, refreshToken: String): Boolean = synchronized(lock) {
		if (active != session || refreshToken.isBlank()) return@synchronized false
		val stored = store.load() ?: return@synchronized false
		store.save(session, stored.token, stored.expiresAt, refreshToken)
		this.refreshToken = refreshToken
		true
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
		refreshToken = restored.refreshToken
		true
	}

	override fun currentSession(): ActiveSession? = synchronized(lock) { active }

	override fun accessTokenFor(session: ActiveSession): String? = tokenFor(session)

	override fun <T> withSessionLock(block: () -> T): T = synchronized(lock, block)

	override fun tokenFor(session: ActiveSession): String? = synchronized(lock) {
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

	override fun refreshTokenFor(session: ActiveSession): String? = synchronized(lock) {
		if (active != session) return@synchronized null
		refreshToken ?: store.load()?.takeIf { it.session == session }?.refreshToken
	}

	override fun replaceTokens(session: ActiveSession, accessToken: String, refreshToken: String, expiresAt: Instant): Boolean = synchronized(lock) {
		if (active != session || accessToken.isBlank() || refreshToken.isBlank()) return@synchronized false
		store.save(session, accessToken, expiresAt, refreshToken)
		this.refreshToken = refreshToken
		true
	}

	fun cache(): EncryptedCache = encryptedCache

	override fun invalidateAndClear() = synchronized(lock) { clearLocked() }

	fun logout() = invalidateAndClear()

	private fun clearLocked() {
		active = null
		refreshToken = null
		tasks.cancelAll()
		store.clear()
		files.cacheFiles().forEach { it.delete() }
		files.temporaryFiles().forEach { it.delete() }
		keys.deleteAll()
	}
}
