package org.materialclass.session

import android.content.Context
import org.materialclass.security.StorageLock
import java.security.SecureRandom
import java.util.concurrent.Executors

@JvmInline
value class AccountId(val value: String) {
	init { require(value.isNotEmpty()) }
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

internal enum class SessionChange { ACTIVATED, EXPIRED, LOGOUT }
internal data class SessionEvent(val epoch: Long, val change: SessionChange, val session: ActiveSession?)

class SessionCoordinator internal constructor(private val clearSession: () -> Unit) {
	constructor(context: Context) : this({ SessionStore.clear(context) })

	companion object {
		private var active: ActiveSession? = null
		private var restorationAllowed = true
		private var revision = 0L
		private var revocationEpoch = 0L
		private var lastEvent = SessionEvent(0L, SessionChange.LOGOUT, null)
		private val observers = mutableSetOf<(Long) -> Unit>()
		private val pending = mutableSetOf<Any>()
		private val leases = mutableSetOf<RequestLease>()
		private val disconnectExecutor = Executors.newCachedThreadPool()

		internal fun permits(lease: RequestLease, account: String): Boolean = synchronized(CancellationLock) {
			pending.isEmpty() && !lease.cancelled && lease in leases && lease.owner === active && active?.account?.value == account
		}

		private fun cancelRequests(owner: ActiveSession? = null) {
			val disconnects = synchronized(CancellationLock) {
				val cancelled = leases.filter { owner == null || it.owner === owner }
				cancelled.forEach { it.cancelled = true }
				leases.removeAll(cancelled.toSet())
				cancelled.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
			}
			disconnects.forEach { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
		}
		private fun matches(expected: Long): Boolean = synchronized(CancellationLock) { pending.isEmpty() && revision == expected }
	}

	private val ownPending = ArrayDeque<Any>()

	internal fun observeRevocations(observer: (Long) -> Unit): Pair<Long, Boolean> = synchronized(CancellationLock) {
		observers.add(observer)
		Pair(revocationEpoch, pending.isNotEmpty())
	}
	internal fun removeRevocationObserver(observer: (Long) -> Unit) = synchronized(CancellationLock) { observers.remove(observer) }
	internal fun revocationEpoch(): Long = synchronized(CancellationLock) { revocationEpoch }
	internal fun event(): SessionEvent = synchronized(CancellationLock) { lastEvent }
	internal fun ownedEpoch(session: ActiveSession?): Long? = synchronized(CancellationLock) { if (session != null && active === session && pending.isEmpty()) revocationEpoch else null }
	private fun notifyRevocation(epoch: Long, callbacks: List<(Long) -> Unit>) {
		callbacks.forEach { observer -> try { observer(epoch) } catch (_: Exception) { } }
	}
	private fun notifyAfterStorage(epoch: Long, callbacks: List<(Long) -> Unit>) {
		disconnectExecutor.execute {
			synchronized(StorageLock) { }
			notifyRevocation(epoch, callbacks)
		}
	}


	fun pendingLogoutTicket(): Any? = synchronized(CancellationLock) { pending.firstOrNull() }
	internal fun isPendingLogout(token: Any): Boolean = synchronized(CancellationLock) { token in pending }

	fun cancelInFlightNow(): Any {
		val token = Any()
		val (disconnects, epoch, callbacks) = synchronized(CancellationLock) {
			pending.add(token)
			ownPending.addLast(token)
			revision++
			leases.forEach { it.cancelled = true }
			val callbacks = leases.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
			leases.clear()
			lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, null)
			Triple(callbacks, revocationEpoch, observers.toList())
		}
		if (Thread.holdsLock(StorageLock)) notifyAfterStorage(epoch, callbacks) else notifyRevocation(epoch, callbacks)
		disconnects.forEach { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
		return token
	}

	internal fun admit(session: ActiveSession): RequestLease? = synchronized(CancellationLock) {
		if (active !== session || pending.isNotEmpty()) null else RequestLease(session).also { leases.add(it) }
	}
	internal fun requestRevision(): Long = synchronized(CancellationLock) { revision }
	internal fun admit(session: ActiveSession, expected: Long): RequestLease? = synchronized(CancellationLock) {
		if (active !== session || pending.isNotEmpty() || revision != expected) null else RequestLease(session).also { leases.add(it) }
	}
	internal fun finish(lease: RequestLease) = synchronized(CancellationLock) {
		leases.remove(lease); lease.cancelled = true; lease.disconnect = null
	}
	internal fun admit(expected: Long): RequestLease? = synchronized(CancellationLock) {
		if (pending.isNotEmpty() || revision != expected) null else RequestLease().also { leases.add(it) }
	}
	internal fun beginLogin(): Long = synchronized(StorageLock) {
		cancelRequests()
		synchronized(CancellationLock) { ++revision }
	}
	internal fun activateIfUnchanged(expected: Long, account: AccountId, beforeActivate: () -> Unit = {}): ActiveSession? = synchronized(StorageLock) {
		if (!matches(expected)) return@synchronized null
		beforeActivate()
		if (!matches(expected)) return@synchronized null
		activateChecked(account, expected)
	}
	internal fun logoutIfNoActive(token: Any? = null): Boolean = synchronized(StorageLock) {
		if (synchronized(CancellationLock) { active != null }) return@synchronized false
		logout(token)
		true
	}
	internal fun cancelLogin(expected: Long) = synchronized(StorageLock) {
		if (matches(expected)) {
			if (synchronized(CancellationLock) { active == null }) logout() else { cancelRequests(); synchronized(CancellationLock) { revision++ } }
		}
	}
	internal fun revokeIfActive(session: ActiveSession, expired: Boolean = false): Boolean = synchronized(StorageLock) {
		val event = synchronized(CancellationLock) {
			if (active !== session) null else {
				active = null; restorationAllowed = false
				lastEvent = SessionEvent(++revocationEpoch, if (expired) SessionChange.EXPIRED else SessionChange.LOGOUT, session)
				Pair(revocationEpoch, observers.toList())
			}
		} ?: return@synchronized false
		notifyAfterStorage(event.first, event.second)
		cancelRequests(session)
		clearSession()
		true
	}
	internal fun activate(account: AccountId): ActiveSession = synchronized(StorageLock) {
		activateChecked(account, requestRevision()) ?: throw IllegalStateException("Session activation cancelled")
	}
	private fun activateChecked(account: AccountId, expected: Long): ActiveSession? {
		val activationRevision = synchronized(CancellationLock) {
			if (pending.isNotEmpty() || revision != expected) null else { active = null; restorationAllowed = false; ++revision }
		} ?: return null
		cancelRequests()
		try {
			clearSession()
		} catch (error: Exception) {
			val event = synchronized(CancellationLock) {
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, null)
				Pair(revocationEpoch, observers.toList())
			}
			notifyAfterStorage(event.first, event.second)
			throw error
		}
		val candidate = ActiveSession(account, CacheNamespace.create())
		val event = synchronized(CancellationLock) {
			if (pending.isNotEmpty() || revision != activationRevision) null else {
				active = candidate
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.ACTIVATED, candidate)
				Pair(revocationEpoch, observers.toList())
			}
		} ?: return null
		notifyAfterStorage(event.first, event.second)
		return candidate
	}
	internal fun beginRestore(): Long? = synchronized(StorageLock) {
		val expected = synchronized(CancellationLock) {
			if (active != null || !restorationAllowed || pending.isNotEmpty()) null else ++revision
		}
		if (expected != null) cancelRequests()
		expected
	}
	internal fun <T> finishRestore(expected: Long, action: () -> T): T? = synchronized(StorageLock) {
		if (!synchronized(CancellationLock) { active == null && restorationAllowed && pending.isEmpty() && revision == expected }) null else action()
	}
	internal fun acceptRestore(account: AccountId, namespace: CacheNamespace): ActiveSession? {
		val result = synchronized(CancellationLock) {
			if (pending.isNotEmpty() || active != null || !restorationAllowed) null else {
				restorationAllowed = false; revision++
				val candidate = ActiveSession(account, namespace)
				active = candidate
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.ACTIVATED, candidate)
				Triple(candidate, revocationEpoch, observers.toList())
			}
		} ?: return null
		notifyAfterStorage(result.second, result.third)
		return result.first
	}
	internal fun rejectRestore() {
		synchronized(CancellationLock) { restorationAllowed = false; revision++ }
		clearSession()
	}
	fun logout(token: Any? = null) = synchronized(StorageLock) {
		if (token != null && !isPendingLogout(token)) return@synchronized
		val (ownedToken, event) = synchronized(CancellationLock) {
			val owned = token ?: ownPending.firstOrNull { it in pending }
			active = null; revision++; restorationAllowed = false
			Pair(owned, if (owned == null) {
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, null)
				Pair(revocationEpoch, observers.toList())
			} else null)
		}
		if (event != null) notifyAfterStorage(event.first, event.second)
		cancelRequests()
		clearSession()
		if (ownedToken != null) finishImmediateLogout(ownedToken)
	}
	internal fun finishImmediateLogout(token: Any) {
		val (epoch, callbacks) = synchronized(CancellationLock) {
			ownPending.remove(token)
			pending.remove(token)
			Pair(revocationEpoch, observers.toList())
		}
		notifyAfterStorage(epoch, callbacks)
	}
	fun <T> ifActive(session: ActiveSession, action: () -> T): T? = synchronized(StorageLock) {
		if (!synchronized(CancellationLock) { active === session && pending.isEmpty() }) return@synchronized null
		action()
	}
}
