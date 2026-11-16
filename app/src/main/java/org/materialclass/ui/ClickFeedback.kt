package org.materialclass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalClickFeedback = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun clickFeedback(action: () -> Unit): () -> Unit {
	val feedback = LocalClickFeedback.current
	return { feedback(); action() }
}

@Composable
internal fun <T> valueFeedback(action: (T) -> Unit): (T) -> Unit {
	val feedback = LocalClickFeedback.current
	return { value -> feedback(); action(value) }
}
