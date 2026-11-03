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

private val CancellationLock = Any()

class RequestLease internal constructor(internal val owner: ActiveSession? = null) {
	@Volatile internal var cancelled = false
	internal var disconnect: (() -> Unit)? = null

	internal fun register(disconnect: () -> Unit): Boolean = synchronized(CancellationLock) {
		if (cancelled) false else { this.disconnect = disconnect; true }
	}

	fun isCancelled(): Boolean = cancelled

	internal fun unregister() = synchronized(CancellationLock) { disconnect = null }
}

class SessionCoordinator internal constructor(private val clearSession: () -> Unit) {
	constructor(context: Context) : this({ SessionStore.clear(context) })

	companion object {
		private var active: ActiveSession? = null
		private var restorationAllowed = true
		private var revision = 0L
		private var cancellationPending = false
		private val leases = mutableSetOf<RequestLease>()
		private val disconnectExecutor = Executors.newCachedThreadPool()

		internal fun permits(lease: RequestLease, account: String): Boolean = synchronized(StorageLock) { synchronized(CancellationLock) {
			!lease.cancelled && lease in leases && lease.owner === active && active?.account?.value == account
		} }

		private fun cancelRequests(owner: ActiveSession? = null) {
			val disconnects = synchronized(CancellationLock) {
				val cancelled = leases.filter { owner == null || it.owner === owner }
				cancelled.forEach { it.cancelled = true }
				leases.removeAll(cancelled.toSet())
				cancelled.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
			}
			disconnects.forEach { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
		}

		private fun nextRevision(): Long = synchronized(CancellationLock) { ++revision }
		private fun matches(expected: Long): Boolean = synchronized(CancellationLock) { !cancellationPending && revision == expected }

	}

	fun cancelInFlightNow() {
		val disconnects = synchronized(CancellationLock) {
			cancellationPending = true
			revision++
			leases.forEach { it.cancelled = true }
			val callbacks = leases.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
			leases.clear()
			callbacks
		}
		disconnects.forEach { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
	}

	internal fun admit(session: ActiveSession): RequestLease? = synchronized(StorageLock) {
		synchronized(CancellationLock) {
			if (active !== session || cancellationPending) null else RequestLease(session).also { leases.add(it) }
		}
	}

	internal fun requestRevision(): Long = synchronized(CancellationLock) { revision }

	internal fun admit(session: ActiveSession, expected: Long): RequestLease? = synchronized(StorageLock) {
		synchronized(CancellationLock) {
			if (active !== session || cancellationPending || revision != expected) null else RequestLease(session).also { leases.add(it) }
		}
	}

	internal fun finish(lease: RequestLease) = synchronized(CancellationLock) {
		leases.remove(lease)
		lease.cancelled = true
		lease.disconnect = null
	}

	internal fun admit(expected: Long): RequestLease? = synchronized(CancellationLock) {
		if (cancellationPending || revision != expected) null else RequestLease().also { leases.add(it) }
	}

	internal fun beginLogin(): Long = synchronized(StorageLock) { cancelRequests(); nextRevision() }

	internal fun activateIfUnchanged(expected: Long, account: AccountId, beforeActivate: () -> Unit = {}): ActiveSession? = synchronized(StorageLock) {
		if (!matches(expected)) return@synchronized null
		beforeActivate()
		activate(account)
	}

	internal fun logoutIfNoActive(): Boolean = synchronized(StorageLock) {
		if (active != null) return@synchronized false
		logout()
		true
	}

	internal fun cancelLogin(expected: Long) = synchronized(StorageLock) {
		if (matches(expected)) {
			if (active == null) logout() else { cancelRequests(); nextRevision() }
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
		nextRevision()
		restorationAllowed = false
		clearSession()
		ActiveSession(account, CacheNamespace.create()).also { active = it }
	}

	internal fun beginRestore(): Long? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed) null else { cancelRequests(); nextRevision() }
	}

	internal fun <T> finishRestore(expected: Long, action: () -> T): T? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed || !matches(expected)) null else action()
	}

	internal fun acceptRestore(account: AccountId, namespace: CacheNamespace): ActiveSession {
		restorationAllowed = false
		nextRevision()
		return ActiveSession(account, namespace).also { active = it }
	}

	internal fun rejectRestore() {
		restorationAllowed = false
		nextRevision()
		clearSession()
	}

	fun logout() = synchronized(StorageLock) {
		cancelRequests()
		active = null
		nextRevision()
		restorationAllowed = false
		clearSession()
		synchronized(CancellationLock) { cancellationPending = false }
	}

	internal fun finishImmediateLogout() = synchronized(CancellationLock) { cancellationPending = false }

	fun <T> ifActive(session: ActiveSession, action: () -> T): T? = synchronized(StorageLock) {
		if (active !== session) return@synchronized null
		action()
	}
}
