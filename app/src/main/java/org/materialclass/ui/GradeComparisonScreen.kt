package org.materialclass.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.materialclass.api.GatewayReadResult
import org.materialclass.R
import org.materialclass.data.GradeComparison


@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun GradeComparisonScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel) {
	val selection = viewModel.comparisonSelection
	var result by remember(selection) { mutableStateOf<GatewayReadResult<GradeComparison>?>(null) }
	LaunchedEffect(selection) {
		result = viewModel.refreshGradeComparison(selection)
	}
	PullRefreshLayout(modifier = Modifier.fillMaxSize().padding(padding), onRefresh = { result = viewModel.refreshGradeComparison(selection) }) { scrollModifier ->
	BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
	val viewportHeight = maxHeight
	Column(modifier = scrollModifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		when (val current = result) {
			null -> Box(modifier = Modifier.fillMaxWidth().height(viewportHeight - 40.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
			GatewayReadResult.Empty -> Text(text("no_data"))
			is GatewayReadResult.Failure -> Text(text("grade_comparison_unavailable"))
			is GatewayReadResult.Success -> ComparisonContent(current.value, showMaximum = selection?.second != "formattedScore", text = text)
		}
	}
	}
	}
}

@Composable
internal fun ComparisonContent(comparison: GradeComparison, showMaximum: Boolean = true, text: (String) -> String) {
	val locale = LocalConfiguration.current.locales[0]
	val context = LocalContext.current
	val own = comparison.studentMark.score
	val counts = comparison.otherMarks.filter { it.score != null }.groupBy { it.score!! }.mapValues { (_, marks) -> marks.sumOf { it.count } }
	val scores = (counts.keys + listOfNotNull(own)).sorted()
	val others = counts.values.sum()
	val lower = if (own == null) null else counts.filterKeys { it < own }.values.sum()
	var selected by remember(comparison) { mutableStateOf(own) }
	var chartVisible by remember(comparison) { mutableStateOf(false) }
	LaunchedEffect(comparison) { chartVisible = true }
	Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), modifier = Modifier.fillMaxWidth().testTag("comparison-summary")) {
		Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("${text("grade_comparison_yours")}: ${own ?: text("no_data")}${comparison.maxScore?.takeIf { showMaximum }?.let { "/$it" }.orEmpty()}", style = MaterialTheme.typography.labelLarge)
			if (lower != null) Text("${text("comparison_above")} $lower / $others", style = MaterialTheme.typography.headlineMedium)
		}
	}
	Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth().testTag("comparison-chart")) {
		Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			val maxCount = (scores.maxOfOrNull { score -> (counts[score] ?: 0) + if (score == own) 1 else 0 } ?: 0).coerceAtLeast(1)
			BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
			Row(modifier = Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
				scores.forEach { score ->
					val count = (counts[score] ?: 0) + if (score == own) 1 else 0
					val height by animateDpAsState(targetValue = (if (chartVisible) 6 + 110 * count / maxCount else 6).dp, animationSpec = spring(), label = "comparisonBar")
					Column(modifier = Modifier.width(42.dp).clickable(onClick = clickFeedback { selected = score }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
						if (score == own) Text(text("comparison_you"), style = MaterialTheme.typography.labelSmall)
						Box(modifier = Modifier.fillMaxWidth().height(height).background(if (score == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)))
						Text(score.toString(), style = MaterialTheme.typography.labelLarge)
					}
				}
			}
			}
			val count = (counts[selected] ?: 0) + if (selected != null && selected == own) 1 else 0
			Text(context.resources.getQuantityString(R.plurals.grade_comparison_count, count, count, selected ?: "-"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
	val total = others + if (own != null) 1 else 0
	val sum = counts.entries.sumOf { it.key.toLong() * it.value } + (own ?: 0)
	val median = if (total == 0) null else {
		val lowerMiddle = (total - 1) / 2
		val upperMiddle = total / 2
		var accumulated = 0
		var lowerScore: Int? = null
		var upperScore: Int? = null
		scores.forEach { score ->
			accumulated += (counts[score] ?: 0) + if (score == own) 1 else 0
			if (lowerScore == null && accumulated > lowerMiddle) lowerScore = score
			if (upperScore == null && accumulated > upperMiddle) upperScore = score
		}
		(lowerScore!!.toDouble() + upperScore!!.toDouble()) / 2
	}
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		listOf(text("comparison_mean") to if (total == 0) "-" else String.format(locale, "%.1f", sum.toDouble() / total), text("comparison_median") to (median?.let { String.format(locale, "%.1f", it) } ?: "-"), text("comparison_best") to (scores.lastOrNull()?.toString() ?: "-")).forEach { (label, value) ->
			Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
				Column(modifier = Modifier.padding(12.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelSmall) }
			}
		}
	}
}
