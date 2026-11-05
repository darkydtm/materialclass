package org.materialclass.session

import org.materialclass.NativeUiState
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

internal class PrivateStateRegistry(initiallyActive: Boolean = true, private val beforeCommitCas: () -> Unit = {}) {
	class Token
	class ControllerId
	class Snapshot internal constructor(val token: Token, val states: Map<ControllerId, NativeUiState>, internal val active: Boolean)

	private val state = AtomicReference(Snapshot(Token(), emptyMap(), initiallyActive))

	fun read(): Snapshot = state.get()
	internal fun compareAndSet(expected: Snapshot, next: Snapshot): Boolean = state.compareAndSet(expected, next)

	private fun snapshot(token: Token, states: Map<ControllerId, NativeUiState>, active: Boolean): Snapshot =
		Snapshot(token, Collections.unmodifiableMap(HashMap(states)), active)

	fun register(): ControllerId {
		val id = ControllerId()
		while (true) {
			val current = state.get()
			if (state.compareAndSet(current, snapshot(current.token, current.states + (id to NativeUiState()), current.active))) return id
		}
	}

	fun unregister(id: ControllerId) {
		while (true) {
			val current = state.get()
			if (id !in current.states) return
			if (state.compareAndSet(current, snapshot(current.token, current.states - id, current.active))) return
		}
	}

	fun commit(token: Token, id: ControllerId, reduce: (NativeUiState) -> NativeUiState): Boolean {
		while (true) {
			val current = state.get()
			if (current.token !== token || !current.active) return false
			val previous = current.states[id] ?: return false
			val updated = reduce(previous)
			val next = snapshot(token, current.states + (id to updated), current.active)
			beforeCommitCas()
			if (state.compareAndSet(current, next)) return true
		}
	}

	enum class LeaseCommit { APPLIED, STALE, CONFLICT }

	fun commitLease(lease: RequestLease, id: ControllerId, reduce: (NativeUiState) -> NativeUiState): LeaseCommit =
		commitLease(lease, id, {}, reduce)

	// Require a pure reducer; return a retryable conflict after eight attempts.
	fun commitLease(lease: RequestLease, id: ControllerId, beforeCas: () -> Unit, reduce: (NativeUiState) -> NativeUiState): LeaseCommit {
		repeat(8) {
			val current = SessionCoordinator.capturePrivateState(this, lease) ?: return LeaseCommit.STALE
			val previous = current.states[id] ?: return LeaseCommit.STALE
			val next = snapshot(current.token, current.states + (id to reduce(previous)), current.active)
			beforeCommitCas()
			beforeCas()
			when (SessionCoordinator.commitPrivateState(this, lease, current, next)) {
				LeaseCommit.APPLIED -> return LeaseCommit.APPLIED
				LeaseCommit.STALE -> return LeaseCommit.STALE
				LeaseCommit.CONFLICT -> Unit
			}
		}
		return LeaseCommit.CONFLICT
	}

	fun revokeAndClear() = transition(false)
	fun activateAndClear() = transition(true)
	fun rotate() {
		while (true) {
			val current = state.get()
			val cancelled = current.states.mapValues { (_, value) -> value.copy(gradesLoading = false) }
			if (state.compareAndSet(current, snapshot(Token(), cancelled, current.active))) return
		}
	}

	private fun transition(active: Boolean) {
		while (true) {
			val current = state.get()
			val cleared = current.states.mapValues { NativeUiState() }
			if (state.compareAndSet(current, snapshot(Token(), cleared, active))) return
		}
	}
}
