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

	fun revokeAndClear() = transition(false)
	fun activateAndClear() = transition(true)

	private fun transition(active: Boolean) {
		while (true) {
			val current = state.get()
			val cleared = current.states.mapValues { NativeUiState() }
			if (state.compareAndSet(current, snapshot(Token(), cleared, active))) return
		}
	}
}
