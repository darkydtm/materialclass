package org.materialclass.session

import android.content.Context
import org.materialclass.security.StorageLock
import java.security.SecureRandom
import java.util.concurrent.Executors

@JvmInline
value class AccountId(val value: String) {
	init {
		require(value.isNotEmpty())
	}

	override fun toString(): String = "AccountId([redacted])"
}

internal class CacheNamespace(val value: String) {
	init { require(value.matches(Regex("[0-9a-f]{64}"))) }
	override fun toString(): String = "CacheNamespace([redacted])"
	companion object {
		fun create(): CacheNamespace = CacheNamespace(ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) })
	}
}

class ActiveSession internal constructor(val account: AccountId, internal val cacheNamespace: CacheNamespace)

class RequestLease internal constructor(internal val owner: ActiveSession? = null) {
	internal var cancelled = false
	internal var disconnect: (() -> Unit)? = null

	internal fun register(disconnect: () -> Unit): Boolean = synchronized(StorageLock) {
		if (cancelled) false else { this.disconnect = disconnect; true }
	}

	internal fun unregister() = synchronized(StorageLock) { disconnect = null }
}

class SessionCoordinator internal constructor(private val clearSession: () -> Unit) {
	constructor(context: Context) : this({ SessionStore.clear(context) })

	companion object {
		private var active: ActiveSession? = null
		private var restorationAllowed = true
		private var revision = 0L
		private val leases = mutableSetOf<RequestLease>()
		private val disconnectExecutor = Executors.newCachedThreadPool()

		private fun cancelRequests(owner: ActiveSession? = null) {
			val cancelled = leases.filter { owner == null || it.owner === owner }
			cancelled.forEach { lease ->
				lease.cancelled = true
				lease.disconnect?.let { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
				lease.disconnect = null
			}
			leases.removeAll(cancelled.toSet())
		}
	}

	internal fun admit(session: ActiveSession): RequestLease? = synchronized(StorageLock) {
		if (active !== session) null else RequestLease(session).also { leases.add(it) }
	}

	internal fun finish(lease: RequestLease) = synchronized(StorageLock) {
		leases.remove(lease)
		lease.cancelled = true
		lease.disconnect = null
	}

	internal fun admit(expected: Long): RequestLease? = synchronized(StorageLock) {
		if (revision != expected) null else RequestLease().also { leases.add(it) }
	}

	internal fun beginLogin(): Long = synchronized(StorageLock) { cancelRequests(); ++revision }

	internal fun activateIfUnchanged(expected: Long, account: AccountId, beforeActivate: () -> Unit = {}): ActiveSession? = synchronized(StorageLock) {
		if (revision != expected) return@synchronized null
		beforeActivate()
		activate(account)
	}

	internal fun logoutIfNoActive(): Boolean = synchronized(StorageLock) {
		if (active != null) return@synchronized false
		logout()
		true
	}

	internal fun cancelLogin(expected: Long) = synchronized(StorageLock) {
		if (revision == expected) {
			if (active == null) logout() else { cancelRequests(); revision++ }
		}
	}

	internal fun revokeIfActive(session: ActiveSession): Boolean = synchronized(StorageLock) {
		if (active !== session) return@synchronized false
		cancelRequests(session)
		active = null
		restorationAllowed = false
		clearSession()
		true
	}

	internal fun activate(account: AccountId): ActiveSession = synchronized(StorageLock) {
		cancelRequests()
		active = null
		revision++
		restorationAllowed = false
		clearSession()
		ActiveSession(account, CacheNamespace.create()).also { active = it }
	}

	internal fun beginRestore(): Long? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed) null else { cancelRequests(); ++revision }
	}

	internal fun <T> finishRestore(expected: Long, action: () -> T): T? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed || revision != expected) null else action()
	}

	internal fun acceptRestore(account: AccountId, namespace: CacheNamespace): ActiveSession {
		restorationAllowed = false
		revision++
		return ActiveSession(account, namespace).also { active = it }
	}

	internal fun rejectRestore() {
		restorationAllowed = false
		revision++
		clearSession()
	}

	fun logout() = synchronized(StorageLock) {
		cancelRequests()
		active = null
		revision++
		restorationAllowed = false
		clearSession()
	}

	fun <T> ifActive(session: ActiveSession, action: () -> T): T? = synchronized(StorageLock) {
		if (active !== session) return@synchronized null
		action()
	}
}
