package org.materialclass.app

internal class TapGestureTracker(private val touchSlop: Float) {
	private var downX: Float? = null
	private var downY: Float? = null

	fun onDown(x: Float, y: Float) {
		downX = x
		downY = y
	}

	fun onMove(x: Float, y: Float) {
		val startX = downX ?: return
		val startY = downY ?: return
		if (kotlin.math.abs(x - startX) >= touchSlop || kotlin.math.abs(y - startY) >= touchSlop) onCancel()
	}

	fun onUp(x: Float, y: Float, handled: Boolean): Boolean {
		val startX = downX
		val startY = downY
		onCancel()
		return handled && startX != null && startY != null &&
			kotlin.math.abs(x - startX) < touchSlop && kotlin.math.abs(y - startY) < touchSlop
	}

	fun onCancel() {
		downX = null
		downY = null
	}
}
