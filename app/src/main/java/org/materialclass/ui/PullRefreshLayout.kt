package org.materialclass.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun PullRefreshLayout(
	modifier: Modifier = Modifier,
	onRefresh: suspend () -> Unit,
	content: @Composable BoxScope.(Modifier) -> Unit,
) {
	val currentRefresh by rememberUpdatedState(onRefresh)
	val threshold = with(LocalDensity.current) { 96.dp.toPx() }
	val state = remember(threshold) { PullRefreshState(threshold) }
	val scope = rememberCoroutineScope()
	val connection = remember(state) {
		object : NestedScrollConnection {
			override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
				if (!state.isRefreshing && source == NestedScrollSource.UserInput && available.y < 0f && state.progress > 0f) {
					state.dragBy(available.y)
					return Offset(0f, available.y)
				}
				return Offset.Zero
			}

			override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
				if (!state.isRefreshing && source == NestedScrollSource.UserInput && available.y > 0f) {
					state.dragBy(available.y)
					return Offset(0f, available.y)
				}
				return Offset.Zero
			}

			override suspend fun onPreFling(available: Velocity): Velocity {
				if (state.release()) {
						scope.launch {
						try {
							currentRefresh()
						} finally {
							state.finish()
						}
					}
				} else if (!state.isRefreshing) {
					state.release()
				}
				return Velocity.Zero
			}
		}
	}
	val animatedProgress by animateFloatAsState(
		targetValue = if (state.isRefreshing) 1f else (state.progress / threshold).coerceIn(0f, 1f),
		animationSpec = tween(durationMillis = 180),
		label = "pull-refresh-progress",
	)
	val indicatorAlpha by animateFloatAsState(
		targetValue = if (state.isRefreshing) 1f else (state.progress / threshold).coerceIn(0f, 1f),
		animationSpec = tween(durationMillis = if (state.isRefreshing) 120 else 220),
		label = "pull-refresh-alpha",
	)
	val indicatorSize by animateDpAsState(
		targetValue = (24f + 16f * animatedProgress).dp,
		animationSpec = tween(durationMillis = 160),
		label = "pull-refresh-size",
	)
	Box(modifier) {
		content(Modifier.nestedScroll(connection))
		val indicatorModifier = Modifier.align(Alignment.TopCenter)
			.offset { IntOffset(0, (state.progress - with(LocalDensity.current) { indicatorSize.toPx() / 2f }).coerceAtLeast(0f).roundToInt()) }
			.size(indicatorSize)
			.graphicsLayer { alpha = indicatorAlpha }
			.testTag("pull-refresh-indicator")
		if (state.isRefreshing) {
			CircularWavyProgressIndicator(modifier = indicatorModifier, color = MaterialTheme.colorScheme.primary)
		} else {
			CircularWavyProgressIndicator(
				progress = { animatedProgress },
				modifier = indicatorModifier,
				color = MaterialTheme.colorScheme.primary,
			)
		}
	}
}
