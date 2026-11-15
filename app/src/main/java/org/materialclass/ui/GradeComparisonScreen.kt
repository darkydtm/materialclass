package org.materialclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.materialclass.api.GatewayReadResult
import org.materialclass.data.GradeComparison
import java.util.Locale

@Composable
internal fun GradeComparisonScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel) {
	val selection = viewModel.comparisonSelection
	var result by remember(selection) { mutableStateOf<GatewayReadResult<GradeComparison>?>(null) }
	LaunchedEffect(selection) {
		result = if (selection == null) GatewayReadResult.Empty else withContext(Dispatchers.IO) { viewModel.gradeComparison(selection.first, selection.second) }
	}
	Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		when (val current = result) {
			null -> CircularProgressIndicator()
			GatewayReadResult.Empty -> Text(text("no_data"))
			is GatewayReadResult.Failure -> Text(text("grade_comparison_unavailable"))
			is GatewayReadResult.Success -> ComparisonContent(current.value, text)
		}
	}
}

@Composable
private fun ComparisonContent(comparison: GradeComparison, text: (String) -> String) {
	val own = comparison.studentMark.score
	val counts = comparison.otherMarks.filter { it.score != null }.groupBy { it.score!! }.mapValues { (_, marks) -> marks.sumOf { it.count } }
	val scores = (counts.keys + listOfNotNull(own)).sorted()
	val others = counts.values.sum()
	val lower = if (own == null) null else counts.filterKeys { it < own }.values.sum()
	var selected by remember(comparison) { mutableStateOf(own) }
	Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("${text("grade_comparison_yours")}: ${own ?: text("no_data")}${comparison.maxScore?.let { "/$it" }.orEmpty()}", style = MaterialTheme.typography.labelLarge)
			if (lower != null) Text("${text("comparison_above")} $lower / $others", style = MaterialTheme.typography.headlineMedium)
		}
	}
	Card(shape = CardShape, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			val maxCount = (counts.values.maxOrNull() ?: 0).coerceAtLeast(1)
			Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
				scores.forEach { score ->
					val count = counts[score] ?: 0
					Column(modifier = Modifier.width(42.dp).clickable { selected = score }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
						if (score == own) Text(text("comparison_you"), style = MaterialTheme.typography.labelSmall)
						Box(modifier = Modifier.fillMaxWidth().height((6 + 110 * count / maxCount).dp).background(if (score == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)))
						Text(score.toString(), style = MaterialTheme.typography.labelLarge)
					}
				}
			}
			Text("${selected ?: "-"}: ${counts[selected] ?: 0}", color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
	val total = others + if (own != null) 1 else 0
	val sum = counts.entries.sumOf { it.key.toLong() * it.value } + (own ?: 0)
	val median = if (total == 0) null else {
		val middle = (total - 1) / 2
		var accumulated = 0
		scores.firstOrNull { score -> accumulated += (counts[score] ?: 0) + if (score == own) 1 else 0; accumulated > middle }
	}
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		listOf(text("comparison_mean") to if (total == 0) "-" else String.format(Locale.getDefault(), "%.1f", sum.toDouble() / total), text("comparison_median") to (median?.toString() ?: "-"), text("comparison_best") to (scores.lastOrNull()?.toString() ?: "-")).forEach { (label, value) ->
			Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
				Column(modifier = Modifier.padding(12.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelSmall) }
			}
		}
	}
}
