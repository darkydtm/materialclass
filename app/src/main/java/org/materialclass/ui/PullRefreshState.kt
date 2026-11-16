package org.materialclass.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PullRefreshState(private val threshold: Float) {
	var progress by mutableFloatStateOf(0f)
		private set
	var isRefreshing by mutableStateOf(false)
		private set

	fun dragBy(distance: Float) {
		if (isRefreshing) return
		if (distance < 0f) {
			progress = (progress + distance).coerceAtLeast(0f)
			return
		}
		if (distance == 0f) return
		progress = (progress + distance / (1f + progress / threshold)).coerceAtMost(threshold * 1.5f)
	}

	fun release(): Boolean {
		if (isRefreshing || progress < threshold) {
			if (!isRefreshing) progress = 0f
			return false
		}
		isRefreshing = true
		progress = threshold
		return true
	}

	fun finish() {
		isRefreshing = false
		progress = 0f
	}
}
