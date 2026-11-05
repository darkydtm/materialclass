package org.materialclass

internal object HomeworkFilterTrace {
	private val events = mutableListOf<String>()
	private var enabled = false

	@Synchronized fun reset() { events.clear(); enabled = true }
	@Synchronized fun stop() { enabled = false; events.clear() }
	@Synchronized fun recordInput(label: String, accepted: Boolean) { if (enabled) events.add("input:$label:$accepted") }
	@Synchronized fun recordToggle(open: Boolean) { if (enabled) events.add("toggle:$open->${!open}") }
	@Synchronized fun recordComposition(open: Boolean) { if (enabled) events.add("composition:$open") }
	@Synchronized fun snapshot(): String = events.joinToString(",")
}
