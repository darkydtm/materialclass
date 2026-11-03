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
		active = null
		restorationAllowed = false
		clearSession()
		true
	}

	internal fun activate(account: AccountId): ActiveSession = synchronized(StorageLock) {
		active = null
		revision++
		restorationAllowed = false
		clearSession()
		ActiveSession(account).also { active = it }
	}

	internal fun beginRestore(): Long? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed) null else ++revision
	}

	internal fun <T> finishRestore(expected: Long, action: () -> T): T? = synchronized(StorageLock) {
		if (active != null || !restorationAllowed || revision != expected) null else action()
	}

	internal fun acceptRestore(account: AccountId): ActiveSession {
		restorationAllowed = false
		revision++
		return ActiveSession(account).also { active = it }
	}

	internal fun rejectRestore() {
		restorationAllowed = false
		revision++
		clearSession()
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
