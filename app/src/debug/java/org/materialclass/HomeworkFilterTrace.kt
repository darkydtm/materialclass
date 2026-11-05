package org.materialclass

internal object HomeworkFilterTrace {
	private val events = mutableListOf<String>()
	private var enabled = false
	private var sequence = 0

	@Synchronized fun reset() { events.clear(); sequence = 0; enabled = true }
	@Synchronized fun stop() { enabled = false; events.clear() }
	private fun record(event: String) { if (enabled) events.add("${++sequence}:$event") }
	@Synchronized fun recordAttempt(label: String) { record("attempt:$label") }
	@Synchronized fun recordInput(label: String, accepted: Boolean) { record("input:$label:$accepted") }
	@Synchronized fun recordToggle(open: Boolean) { record("toggle:$open->${!open}") }
	@Synchronized fun recordComposition(open: Boolean) { record("composition:$open") }
	@Synchronized fun snapshot(): String = events.joinToString(",")
	@Synchronized fun count(kind: String): Int = events.count { it.substringAfter(':').startsWith(kind) }
}
