package org.materialclass.session

@JvmInline
value class AccountId(val value: String) {
	init {
		require(value.isNotEmpty())
	}

	override fun toString(): String = "AccountId([redacted])"
}

@JvmInline
value class Generation(val value: Long)

data class ActiveSession(val account: AccountId, val generation: Generation)

class SessionCoordinator {
	private var generation = 0L
	private var active: ActiveSession? = null

	@Synchronized
	fun activate(account: AccountId): ActiveSession {
		generation = Math.addExact(generation, 1)
		return ActiveSession(account, Generation(generation)).also { active = it }
	}

	@Synchronized
	fun logout() {
		active = null
		generation = Math.addExact(generation, 1)
	}

	@Synchronized
	fun <T> ifActive(session: ActiveSession, action: () -> T): T? {
		if (active != session) return null
		return action()
	}
}
