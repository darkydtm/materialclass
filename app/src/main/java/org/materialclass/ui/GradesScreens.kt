package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Surface

import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.materialclass.api.GatewayReadResult
import org.materialclass.data.GradeComparison

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.materialclass.data.GradeSubject
import org.materialclass.data.GradeSchedule
import org.materialclass.grades.GradeCalculator
import org.materialclass.grades.GradeCategory
import org.materialclass.grades.GradeComponent

internal fun gradeComponents(schedules: List<GradeSchedule>): List<GradeComponent> = schedules.flatMap { schedule ->
	fun component(category: GradeCategory, value: String?, maximum: String?): GradeComponent? {
		val score = value?.toDoubleOrNull() ?: return null
		val max = maximum?.toDoubleOrNull() ?: return null
		if (max <= 0 || score !in 0.0..max) return null
		return GradeComponent(category, score * 100 / max)
	}
	listOfNotNull(
		component(GradeCategory.FO, schedule.score, schedule.markMax?.toString()),
		component(GradeCategory.SOR, schedule.sor, schedule.sorMax),
		component(GradeCategory.SOCH, schedule.soch, schedule.sochMax),
	)
}

@Composable
internal fun DiaryScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, navigator: AppNavigator, onSubjectSelected: (String) -> Unit) {
	val quarter = (viewModel.gradesPeriod.toIntOrNull() ?: 1) - 1
	LaunchedEffect(Unit) { viewModel.loadGrades(viewModel.gradesPeriod) }
	val grades = viewModel.gradesState.readyValue()
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		item {
			Card(shape = CardShape, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
			Column(modifier = Modifier.padding(12.dp)) {
			Text(text("quarter"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
			Surface(shape = NotificationShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.padding(5.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					(0..3).forEach { index ->
						Surface(
							modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { viewModel.selectGradesPeriod((index + 1).toString()) },
							shape = RoundedCornerShape(14.dp),
							color = if (quarter == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
							contentColor = if (quarter == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
						) {
							Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
								Text((index + 1).toString(), style = MaterialTheme.typography.titleMedium)
							}
						}
					}
				}
			}
		}
			}
			}
		if (viewModel.quarterNotStarted && (viewModel.gradesState is org.materialclass.data.DataState.Empty || (viewModel.gradesState is org.materialclass.data.DataState.Ready && grades?.subjects?.none { it.schedules.any { schedule -> schedule.score != null || schedule.sor != null || schedule.soch != null } || it.scores.isNotEmpty() || !it.finalScore.isNullOrBlank() } == true))) {
			item { Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) { Text(text("quarter_not_started"), modifier = Modifier.padding(20.dp)) } }
		} else if (grades == null) {
			item { StatePanel(text, viewModel.gradesState.toUiState(), { viewModel.retry(AppRoute.Diary) }) }
		} else if (grades.subjects.isEmpty()) {
			item { StatePanel(text, UiContent.Empty) }
		} else {
			items(
				count = grades.subjects.size,
				key = { index -> stableItemKey("subject", index, grades.subjects[index].subjectName) },
				contentType = { "subject" },
			) { index ->
				val subject = grades.subjects[index]
				SubjectCard(subject, text, viewModel, navigator) {
					navigator.goFrom(AppRoute.Diary, AppRoute.SubjectDetail) { subject.subjectName?.let(onSubjectSelected) }
				}
			}
		}
	}
}

@Composable
private fun SubjectCard(subject: GradeSubject, text: (String) -> String, viewModel: SchoolViewModel, navigator: AppNavigator, onClick: () -> Unit) {
	val finalScore = effectiveFinalScore(subject)
	val displayedScore = assessmentText(finalScore, subject.scoreType, text("credit"), text("fail"), text("no_data"))
	Card(onClick = onClick, shape = RowShape, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
		Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(subject.subjectName ?: text("unknown"), modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleLarge)
				Surface(shape = RoundedCornerShape(10.dp), color = gradeContainerColor(finalScore, subject.scoreType, 5.0), contentColor = gradeContentColor(finalScore, subject.scoreType, 5.0)) {
					Text(displayedScore, modifier = Modifier.widthIn(min = 34.dp).padding(horizontal = 8.dp, vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
				}
			}
				Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
					if (subject.schedules.any { it.score != null || it.sor != null || it.soch != null }) {
						var first = true
						@Composable fun separator() { if (!first) VerticalDivider(modifier = Modifier.height(36.dp)); first = false }
						subject.schedules.forEach { schedule ->
							if (schedule.score != null) { separator(); GradeTile(schedule.score, subject.scoreType, schedule.date, null, text, schedule.markMax?.toDouble(), uuid = schedule.uuid, comparisonType = "formattedScore", viewModel = viewModel, navigator = navigator) }
							if (schedule.sor != null) { separator(); GradeTile(schedule.sor, subject.scoreType, schedule.date, text("grade_category_sor"), text, schedule.sorMax?.toDoubleOrNull(), schedule.sorMax, schedule.uuid, "sor", viewModel, navigator) }
							if (schedule.soch != null) { separator(); GradeTile(schedule.soch, subject.scoreType, schedule.date, text("grade_category_soch"), text, schedule.sochMax?.toDoubleOrNull(), schedule.sochMax, schedule.uuid, "soch", viewModel, navigator) }
						}
					} else {
						subject.scores.forEachIndexed { index, score -> if (index > 0) VerticalDivider(modifier = Modifier.height(36.dp)); GradeTile(score, subject.scoreType, null, null, text) }
					}
				}
		}
	}
}

@Composable
internal fun gradeContainerColor(value: String?, scoreType: String?, maximum: Double? = null): androidx.compose.ui.graphics.Color {
	val colors = MaterialTheme.colorScheme
	return when (assessmentKind(value, scoreType)) {
		AssessmentKind.Fail -> colors.errorContainer
		AssessmentKind.Credit -> colors.primaryContainer
		AssessmentKind.Empty -> colors.surfaceContainerHigh
		AssessmentKind.Numeric -> {
			val number = value?.substringBefore('/')?.trim()?.toDoubleOrNull()
			val max = maximum ?: value?.substringAfter('/', "")?.toDoubleOrNull()
			val percent = if (number != null && max != null && max > 0) number * 100 / max else if (number != null) number * 10 else null
			if (percent == null || !percent.isFinite()) colors.surfaceContainerHigh
			else Color.hsl(gradeHue(percent), 0.48f, if (colors.surface.luminance() < 0.5f) 0.27f else 0.86f)
		}
	}
}

@Composable
internal fun gradeContentColor(value: String?, scoreType: String?, maximum: Double? = null): androidx.compose.ui.graphics.Color {
	val colors = MaterialTheme.colorScheme
	val container = gradeContainerColor(value, scoreType, maximum)
	return when (assessmentKind(value, scoreType)) {
		AssessmentKind.Numeric -> if (container.luminance() < 0.5f) Color.White else Color.Black
		else -> when (container) {
		colors.errorContainer -> colors.onErrorContainer
		colors.tertiaryContainer -> colors.onTertiaryContainer
		colors.secondaryContainer -> colors.onSecondaryContainer
		colors.primaryContainer -> colors.onPrimaryContainer
		else -> colors.onSurface
		}
	}
}

@Composable
internal fun GradeTile(value: String?, scoreType: String?, date: String?, type: String?, text: (String) -> String, maximum: Double? = null, maximumLabel: String? = null, uuid: String? = null, comparisonType: String? = null, viewModel: SchoolViewModel? = null, navigator: AppNavigator? = null) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
		if (!type.isNullOrBlank()) Text(type, style = MaterialTheme.typography.labelSmall, maxLines = 1)
		Surface(onClick = { if (!uuid.isNullOrBlank() && comparisonType != null && viewModel != null && navigator != null) { viewModel.selectComparison(uuid, comparisonType); navigator.go(AppRoute.GradeComparison) } }, shape = RoundedCornerShape(11.dp), color = gradeContainerColor(value, scoreType, maximum), contentColor = gradeContentColor(value, scoreType, maximum), modifier = Modifier.widthIn(min = 44.dp)) {
			Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
				Text(assessmentText(value, scoreType, text("credit"), text("fail"), text("no_data")), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		if (!date.isNullOrBlank()) Text(date.substringBefore('T'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SubjectDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, navigator: AppNavigator) {
	LaunchedEffect(viewModel.gradesPeriod) { viewModel.loadGrades(viewModel.gradesPeriod) }
	val subject = viewModel.gradesState.readyValue()?.subjects?.firstOrNull { it.subjectName == selectedSubject }
	if (subject == null) {
		StateScreen(padding, text, "subject_title", "subject_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.SubjectDetail) }
		return
	}
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		val credit = isCreditSubject(subject)
		val finalScore = effectiveFinalScore(subject)
		Text(subject.subjectName.orEmpty(), style = MaterialTheme.typography.headlineLarge)
		Card(shape = CardShape, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = gradeContainerColor(finalScore, subject.scoreType, 5.0), contentColor = gradeContentColor(finalScore, subject.scoreType, 5.0))) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("final_grade"), style = MaterialTheme.typography.labelLarge)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(assessmentText(finalScore, subject.scoreType, text("credit"), text("fail"), text("no_data")), style = MaterialTheme.typography.displaySmall)
					GradeCalculator.calculate(gradeComponents(subject.schedules)).percentage?.let { percentage ->
						Text("(${percentage.toInt()}%)", style = MaterialTheme.typography.titleMedium)
					}
				}
			}
		}
		if (!credit) {
			Text(text("calculation"), style = MaterialTheme.typography.titleMedium)
			Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				val components = gradeComponents(subject.schedules)
				val weights = mapOf(GradeCategory.FO to 50, GradeCategory.SOR to 25, GradeCategory.SOCH to 25)
				val availableWeight = components.map { it.category }.distinct().sumOf { weights.getValue(it) }
				GradeCategory.entries.forEach { category ->
					val values = components.filter { it.category == category }.mapNotNull { it.score }
					val contribution = if (values.isEmpty() || availableWeight == 0) null else (values.average() * weights.getValue(category) / availableWeight).toInt()
					val average = values.takeIf { it.isNotEmpty() }?.average()
					val color = gradeContainerColor(average?.toString(), null, 100.0)
					Card(shape = RowShape, modifier = Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = color, contentColor = gradeContentColor(average?.toString(), null, 100.0))) {
						Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
							Text(text(when (category) { GradeCategory.FO -> "grade_category_fo"; GradeCategory.SOR -> "grade_category_sor"; GradeCategory.SOCH -> "grade_category_soch" }), style = MaterialTheme.typography.labelMedium)
							Text(if (contribution == null) text("no_data") else "$contribution/${weights.getValue(category)}%", style = MaterialTheme.typography.titleMedium)
						}
					}
				}
			}

		}
		val scoredSchedules = subject.schedules.filter { it.score != null || it.sor != null || it.soch != null }
		if (scoredSchedules.isNotEmpty()) {
			Text("${scoredSchedules.sumOf { listOf(it.score, it.sor, it.soch).count { value -> value != null } }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
			androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				scoredSchedules.forEach { schedule ->
					if (schedule.score != null) GradeTile(schedule.score, subject.scoreType, schedule.date, text("grade_category_fo"), text, schedule.markMax?.toDouble(), uuid = schedule.uuid, comparisonType = "formattedScore", viewModel = viewModel, navigator = navigator)
					if (schedule.sor != null) GradeTile(schedule.sor, subject.scoreType, schedule.date, text("grade_category_sor"), text, schedule.sorMax?.toDoubleOrNull(), schedule.sorMax, schedule.uuid, "sor", viewModel, navigator)
					if (schedule.soch != null) GradeTile(schedule.soch, subject.scoreType, schedule.date, text("grade_category_soch"), text, schedule.sochMax?.toDoubleOrNull(), schedule.sochMax, schedule.uuid, "soch", viewModel, navigator)
				}
			}
		}
	}
}

@Composable
internal fun CalculatorScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, missing: Boolean) {
	LaunchedEffect(viewModel.gradesPeriod) { viewModel.loadGrades(viewModel.gradesPeriod) }
	val subject = viewModel.gradesState.readyValue()?.subjects?.firstOrNull { it.subjectName == selectedSubject }
	val components = remember(subject) {
		gradeComponents(subject?.schedules.orEmpty())
	}
	val estimate = remember(components) { GradeCalculator.calculate(components) }
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("calculation_estimate"), style = MaterialTheme.typography.labelLarge)
				Text(if (estimate.percentage == null) text("no_data") else "${estimate.percentage.toInt()}%", style = MaterialTheme.typography.displaySmall)
				Text(text("calculation_disclaimer"), color = MaterialTheme.colorScheme.onTertiaryContainer)
			}
		}
		Text(text(if (estimate.missingCategories.isEmpty()) "calculation_complete" else "calculation_missing"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		if (missing || subject?.schedules.isNullOrEmpty()) {
			Card(shape = RowShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
				Text(text("calculator_missing"), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onErrorContainer)
			}
		}
	}
}
