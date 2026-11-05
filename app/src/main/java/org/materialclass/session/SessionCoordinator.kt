package org.materialclass.session

import android.content.Context
import org.materialclass.security.StorageLock
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

class SessionCoordinator internal constructor(private val clearSession: () -> Unit, private val clearCache: (CacheNamespace) -> Unit, private val journal: CleanupJournal? = null, private val invalidateSession: () -> Unit = {}) {
	internal constructor(clearSession: () -> Unit) : this(clearSession, {})
	constructor(context: Context) : this({ SessionStore.clear(context) }, { org.materialclass.cache.EncryptedCache.clearNamespace(context, it) }, FileCleanupJournal(context), { org.materialclass.security.KeystoreKeys().delete("session") })

	private val recovery by lazy { journal?.let { CleanupRecovery(it, clearCache, clearSession, invalidateSession) } }
	private fun recoverCleanup() { recovery?.recover() }

	companion object {
		private var active: ActiveSession? = null
		private var restorationAllowed = true
		private var revision = 0L
		private var loginGeneration = 0L
		private var revocationEpoch = 0L
		private var lastEvent = SessionEvent(0L, SessionChange.LOGOUT, null)
		private val observers = mutableSetOf<(Long) -> Unit>()
		private val uiObservers = mutableSetOf<(Long, () -> Unit) -> Unit>()
		private val uiAcknowledgements = mutableMapOf<Any, CountDownLatch>()
		private val uiPendingObservers = mutableMapOf<Any, MutableMap<(Long, () -> Unit) -> Unit, () -> Unit>>()
		private val cleanedTickets = mutableSetOf<Any>()
		private val failedUiCallbacks = mutableMapOf<Any, MutableList<() -> Unit>>()
		private val uiFailures = mutableMapOf<() -> Unit, () -> Unit>()
		private val pending = mutableSetOf<Any>()
		private val revoked = mutableMapOf<Any, CacheNamespace?>()
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
	internal fun observeUiRevocations(observer: (Long, () -> Unit) -> Unit): Pair<Long, Boolean> = synchronized(CancellationLock) {
		uiObservers.add(observer)
		Pair(revocationEpoch, pending.isNotEmpty())
	}
	internal fun removeUiRevocationObserver(observer: (Long, () -> Unit) -> Unit) {
		val acknowledgements = synchronized(CancellationLock) {
			uiObservers.remove(observer)
			uiPendingObservers.values.mapNotNull { it[observer] }
		}
		acknowledgements.forEach { it() }
	}
	internal fun failUiRevocation(acknowledge: () -> Unit) {
		val failure = synchronized(CancellationLock) { uiFailures[acknowledge] }
		failure?.invoke()
	}
	internal fun uiAcknowledgement(token: Any?): CountDownLatch? = synchronized(CancellationLock) { uiAcknowledgements[token] }
	internal fun awaitUiAcknowledgement(acknowledgement: CountDownLatch?) {
		if (acknowledgement == null || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
		while (!acknowledgement.await(100, TimeUnit.MILLISECONDS)) {
			if (synchronized(CancellationLock) { failedUiCallbacks.keys.any { uiAcknowledgements[it] === acknowledgement } }) throw IllegalStateException("UI revocation failed")
		}
	}
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

	private fun prepareUiRevocation(token: Any, epoch: Long): () -> Unit {
		val callbacks = uiObservers.toList()
		val acknowledgement = CountDownLatch(callbacks.size)
		if (callbacks.isNotEmpty()) uiAcknowledgements[token] = acknowledgement
		val deliveries = callbacks.map { observer ->
			val completed = AtomicBoolean(false)
			val acknowledge = {
				if (completed.compareAndSet(false, true)) {
					synchronized(CancellationLock) {
						uiPendingObservers[token]?.remove(observer)?.let { uiFailures.remove(it) }
						acknowledgement.countDown()
						if (acknowledgement.count == 0L) {
							uiPendingObservers.remove(token)
							uiAcknowledgements.remove(token)
							failedUiCallbacks.remove(token)
							if (token in cleanedTickets) finishImmediateLogout(token)
						}
					}
				}
				Unit
			}
			uiPendingObservers.getOrPut(token) { mutableMapOf() }[observer] = acknowledge
			lateinit var deliver: () -> Unit
			val fail = {
				synchronized(CancellationLock) {
					if (!completed.get()) {
						val failures = failedUiCallbacks.getOrPut(token) { mutableListOf() }
						if (deliver !in failures) failures.add(deliver)
					}
				}
				Unit
			}
			uiFailures[acknowledge] = fail
			deliver = {
				if (!completed.get()) try { observer(epoch, acknowledge) } catch (_: Exception) { fail() }
			}
			deliver
		}
		return { deliveries.forEach { it() } }
	}

	private fun deliverUiAfterStorage(deliver: () -> Unit) {
		if (Thread.holdsLock(StorageLock)) disconnectExecutor.execute { synchronized(StorageLock) { }; deliver() } else deliver()
	}

	fun cancelInFlightNow(): Any {
		val token = Any()
		val (disconnects, epoch, callbacks, deliver) = synchronized(CancellationLock) {
			pending.add(token)
			revoked[token] = active?.cacheNamespace
			ownPending.addLast(token)
			revision++
			leases.forEach { it.cancelled = true }
			val disconnects = leases.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
			leases.clear()
			lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, null)
			UiNotification(disconnects, revocationEpoch, observers.toList(), prepareUiRevocation(token, revocationEpoch))
		}
		deliverUiAfterStorage(deliver)
		if (Thread.holdsLock(StorageLock)) notifyAfterStorage(epoch, callbacks) else notifyRevocation(epoch, callbacks)
		disconnects.forEach { disconnect -> disconnectExecutor.execute { try { disconnect() } catch (_: Exception) { } } }
		return token
	}

	private data class UiNotification(val disconnects: List<() -> Unit>, val epoch: Long, val callbacks: List<(Long) -> Unit>, val deliver: () -> Unit)

	internal fun admit(session: ActiveSession): RequestLease? = synchronized(CancellationLock) {
		if (active !== session || pending.isNotEmpty()) null else RequestLease(session).also { leases.add(it) }
	}
	internal fun requestRevision(): Long = synchronized(CancellationLock) { revision }
	internal fun loginGeneration(): Long = synchronized(CancellationLock) { loginGeneration }
	internal fun invalidateSchoolRequests(session: ActiveSession, except: RequestLease) {
		val disconnects = synchronized(CancellationLock) {
			if (active !== session || pending.isNotEmpty()) return
			revision++
			val cancelled = leases.filter { it.owner === session && it !== except }
			cancelled.forEach { it.cancelled = true }
			leases.removeAll(cancelled.toSet())
			cancelled.mapNotNull { lease -> lease.disconnect.also { lease.disconnect = null } }
		}
		disconnects.forEach { disconnect -> disconnectExecutor.execute {
			synchronized(StorageLock) { }
			try { disconnect() } catch (_: Exception) { }
		} }
	}
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
		recoverCleanup()
		cancelRequests()
		synchronized(CancellationLock) { loginGeneration++; ++revision }
	}
	internal fun activateIfUnchanged(expected: Long, account: AccountId, beforeActivate: () -> Unit = {}): ActiveSession? = synchronized(StorageLock) {
		if (!matches(expected)) return@synchronized null
		beforeActivate()
		if (!matches(expected)) return@synchronized null
		activateChecked(account, expected)
	}
	internal fun logoutIfNoActive(token: Any? = null, selected: (Any) -> Unit = {}): Boolean = synchronized(StorageLock) {
		if (synchronized(CancellationLock) { active != null }) return@synchronized false
		logout(token, selected)
		true
	}
	internal fun cancelLogin(expected: Long) = synchronized(StorageLock) {
		if (matches(expected)) {
			if (synchronized(CancellationLock) { active == null }) logout() else { cancelRequests(); synchronized(CancellationLock) { revision++ } }
		}
	}
	private fun cleanup(namespace: CacheNamespace?) {
		if (namespace == null && synchronized(CancellationLock) { revoked.values.any { it != null } }) throw LogoutStorageException(java.io.IOException("Another cleanup is pending"))
		val durable = recovery
		if (durable != null) { durable.cleanup(namespace); return }
		var failure: Exception? = null
		try { if (namespace != null) clearCache(namespace) } catch (error: Exception) { failure = error }
		try { clearSession() } catch (error: Exception) {
			if (failure == null) failure = error else failure.addSuppressed(error)
		}
		failure?.let { throw it }
	}

	private data class UiNotificationWithToken(val token: Any, val epoch: Long, val callbacks: List<(Long) -> Unit>, val deliver: () -> Unit)

	internal fun revokeIfActive(session: ActiveSession, expired: Boolean = false, selected: (Any) -> Unit = {}): Boolean = synchronized(StorageLock) {
		val result = synchronized(CancellationLock) {
			if (active !== session || pending.isNotEmpty()) null else {
				active = null; restorationAllowed = false
				val token = Any()
				pending.add(token)
				revoked[token] = session.cacheNamespace
				ownPending.addLast(token)
				lastEvent = SessionEvent(++revocationEpoch, if (expired) SessionChange.EXPIRED else SessionChange.LOGOUT, session)
				UiNotificationWithToken(token, revocationEpoch, observers.toList(), prepareUiRevocation(token, revocationEpoch))
			}
		} ?: return@synchronized false
		selected(result.token)
		deliverUiAfterStorage(result.deliver)
		notifyAfterStorage(result.epoch, result.callbacks)
		cancelRequests(session)
		cleanup(session.cacheNamespace)
		finishImmediateLogout(result.token)
		true
	}
	internal fun activate(account: AccountId): ActiveSession = synchronized(StorageLock) {
		activateChecked(account, requestRevision()) ?: throw IllegalStateException("Session activation cancelled")
	}
	private fun activateChecked(account: AccountId, expected: Long): ActiveSession? {
		recoverCleanup()
		val activation = synchronized(CancellationLock) {
			if (pending.isNotEmpty() || revision != expected) null else {
				val previous = active
				active = null; restorationAllowed = false
				Pair(++revision, previous)
			}
		} ?: return null
		val (activationRevision, previous) = activation
		cancelRequests()
		try {
			cleanup(previous?.cacheNamespace)
		} catch (error: Exception) {
			val event = synchronized(CancellationLock) {
				val token = Any()
				pending.add(token)
				revoked[token] = previous?.cacheNamespace
				ownPending.addLast(token)
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, previous)
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
		recoverCleanup()
		val expected = synchronized(CancellationLock) {
			if (active != null || !restorationAllowed || pending.isNotEmpty()) null else ++revision
		}
		if (expected != null) cancelRequests()
		expected
	}
	internal fun <T> finishRestore(expected: Long, action: () -> T): T? = synchronized(StorageLock) {
		if (!synchronized(CancellationLock) { active == null && restorationAllowed && pending.isEmpty() && revision == expected }) null else action()
	}
	internal fun acceptRestore(account: AccountId, namespace: CacheNamespace, notify: Boolean = true): ActiveSession? {
		val result = synchronized(CancellationLock) {
			if (pending.isNotEmpty() || active != null || !restorationAllowed) null else {
				restorationAllowed = false; revision++
				val candidate = ActiveSession(account, namespace)
				active = candidate
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.ACTIVATED, candidate)
				Triple(candidate, revocationEpoch, observers.toList())
			}
		} ?: return null
		if (notify) notifyAfterStorage(result.second, result.third)
		return result.first
	}
	internal fun notifyRestore(session: ActiveSession) {
		val event = synchronized(CancellationLock) {
			if (active !== session || pending.isNotEmpty() || lastEvent.session !== session || lastEvent.change != SessionChange.ACTIVATED) null
			else Pair(lastEvent.epoch, observers.toList())
		} ?: return
		notifyRevocation(event.first, event.second)
	}
	internal fun rejectRestore(namespace: CacheNamespace) {
		val token = synchronized(CancellationLock) {
			restorationAllowed = false; revision++
			Any().also { pending.add(it); revoked[it] = namespace; ownPending.addLast(it) }
		}
		cleanup(namespace)
		finishImmediateLogout(token)
	}
	fun logout(token: Any? = null) = logout(token) { }
	internal fun logout(token: Any?, selected: (Any) -> Unit) {
		val nested = Thread.holdsLock(StorageLock)
		var acknowledgement: CountDownLatch? = null
		var retries = emptyList<() -> Unit>()
		synchronized(StorageLock) {
		if (token != null && !isPendingLogout(token)) return@synchronized
		val (ownedToken, event) = synchronized(CancellationLock) {
			val existing = token ?: ownPending.firstOrNull { it in pending }
			val owned = existing ?: Any().also { pending.add(it); revoked[it] = active?.cacheNamespace; ownPending.addLast(it) }
			val namespace = revoked[owned]
			if (active?.cacheNamespace === namespace || namespace == null) active = null
			revision++; restorationAllowed = false
			Pair(owned, if (existing == null) {
				lastEvent = SessionEvent(++revocationEpoch, SessionChange.LOGOUT, null)
				Triple(revocationEpoch, observers.toList(), prepareUiRevocation(owned, revocationEpoch))
			} else null)
		}
		selected(ownedToken)
		acknowledgement = uiAcknowledgement(ownedToken)
		if (event != null) { deliverUiAfterStorage(event.third); notifyAfterStorage(event.first, event.second) }
		cancelRequests()
		cleanup(synchronized(CancellationLock) { revoked[ownedToken] })
		retries = synchronized(CancellationLock) { failedUiCallbacks.remove(ownedToken)?.toList() ?: emptyList() }
		finishImmediateLogout(ownedToken)
		}
		if (nested) disconnectExecutor.execute { synchronized(StorageLock) { }; retries.forEach { it() } }
		else { retries.forEach { it() }; awaitUiAcknowledgement(acknowledgement) }
	}
	internal fun finishImmediateLogout(token: Any) {
		val (epoch, callbacks) = synchronized(CancellationLock) {
			if (token !in pending) return
			if (uiAcknowledgements[token]?.count?.let { it > 0 } == true) {
				cleanedTickets.add(token)
				return
			}
			cleanedTickets.remove(token)
			ownPending.remove(token)
			pending.remove(token)
			revoked.remove(token)
			Pair(revocationEpoch, observers.toList())
		}
		notifyAfterStorage(epoch, callbacks)
	}
	fun <T> ifActive(session: ActiveSession, action: () -> T): T? = synchronized(StorageLock) {
		if (!synchronized(CancellationLock) { active === session && pending.isEmpty() }) return@synchronized null
		action()
	}
}
