package org.materialclass.session

import android.content.Context
import org.materialclass.security.StorageLock

@JvmInline
value class AccountId(val value: String) {
	init {
		require(value.isNotEmpty())
	}

	override fun toString(): String = "AccountId([redacted])"
}

class ActiveSession internal constructor(val account: AccountId)

class SessionCoordinator internal constructor(private val clearSession: () -> Unit) {
	constructor(context: Context) : this({ SessionStore.clear(context) })

	companion object {
		private var active: ActiveSession? = null
		private var restorationAllowed = true
		private var revision = 0L
	}

	internal fun beginLogin(): Long = synchronized(StorageLock) { ++revision }

	internal fun activateIfUnchanged(expected: Long, account: AccountId): ActiveSession? = synchronized(StorageLock) {
		if (revision != expected) return@synchronized null
		activate(account)
	}

	internal fun cancelLogin(expected: Long) = synchronized(StorageLock) {
		if (revision == expected) logout()
	}

	internal fun revokeIfActive(session: ActiveSession): Boolean = synchronized(StorageLock) {
		if (active !== session) return@synchronized false
		logout()
		true
	}

	internal fun activate(account: AccountId): ActiveSession = synchronized(StorageLock) {
		active = null
		revision++
		restorationAllowed = false
		clearSession()
		ActiveSession(account).also { active = it }
	}

	internal fun restore(account: () -> AccountId?): ActiveSession? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed) return@synchronized null
		val restored = account()
		restorationAllowed = false
		restored?.let { revision++; ActiveSession(it).also { session -> active = session } }
	}

	fun logout() = synchronized(StorageLock) {
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
