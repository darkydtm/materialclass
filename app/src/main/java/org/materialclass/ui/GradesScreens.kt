package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.materialclass.grades.GradeCalculator

import org.materialclass.grades.GradeComponent

@Composable
internal fun DiaryScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, navigator: AppNavigator, onSubjectSelected: (String) -> Unit) {
	var quarter by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(quarter) { viewModel.loadGrades((quarter + 1).toString()) }
	val grades = viewModel.gradesState.readyValue()
	val unknown = text("unknown")
	val noData = text("no_data")
	val subjectRows = remember(grades?.subjects, unknown, noData) { grades?.let { gradeSubjectRows(it.subjects, unknown, noData) }.orEmpty() }

	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		item(key = "diary:quarter-picker", contentType = "diary-header") {
			Card(shape = NotificationShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.padding(5.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					(0..3).forEach { index ->
						FilterChip(
							selected = quarter == index,
							onClick = { quarter = index },
							shape = QuarterChipShape,
							label = { Text((index + 1).toString()) },
						)
					}
				}
			}
		}
		if (grades == null) {
			item(key = "diary:state", contentType = "state") { StatePanel(text, viewModel.gradesState.toUiState(), { viewModel.retry(AppRoute.Diary) }) }
		} else if (grades.subjects.isEmpty()) {
			item(key = "diary:empty", contentType = "state") { StatePanel(text, UiContent.Empty) }
		} else {
			items(
				count = subjectRows.size,
				key = { index -> subjectRows[index].stableKey },
				contentType = { "subject" },
			) { index ->
				val subject = subjectRows[index]
				SubjectCard(subject) {
					onSubjectSelected(subject.stableKey)
					navigator.go(AppRoute.SubjectDetail)
				}
			}
		}
	}
}

@Composable
private fun SubjectCard(subject: SubjectRow, onClick: () -> Unit) {
	Card(onClick = onClick, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(subject.name, style = MaterialTheme.typography.titleLarge)
				Text(subject.finalScore, style = MaterialTheme.typography.titleMedium)
			}
			Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(subject.quarter1, modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(subject.quarter2, modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(subject.quarter3, modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(subject.quarter4, modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

@Composable
internal fun SubjectDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, navigator: AppNavigator) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	val subject = remember(viewModel.gradesState?.readyValue(), selectedSubject) {
		findGradeSubject(viewModel.gradesState.readyValue()?.subjects.orEmpty(), selectedSubject)
	}
	if (subject == null) {
		StateScreen(padding, text, "subject_title", "subject_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.SubjectDetail) }
		return
	}
	val unknown = text("unknown")
	val noData = text("no_data")
	val finalGrade = text("final_grade")
	val calculation = text("calculation")
	val calculationTitle = text("calculation_title")
	val subjectRow = remember(subject, unknown, noData) { gradeSubjectDetailRow(subject, unknown, noData) }
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		item(key = "subject-detail:title", contentType = "subject-detail-header") {
			Text(subjectRow.title, style = MaterialTheme.typography.headlineLarge)
		}
		item(key = "subject-detail:summary", contentType = "subject-detail-summary") {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(finalGrade, style = MaterialTheme.typography.labelLarge)
					Text(subjectRow.finalGrade, style = MaterialTheme.typography.displaySmall)
				}
			}
		}
		item(key = "subject-detail:calculation", contentType = "subject-detail-header") {
			Text(calculation, style = MaterialTheme.typography.titleMedium)
		}
		if (!subjectRow.hasSchedules) {
			item(key = "subject-detail:no-schedules", contentType = "subject-detail-empty") {
				Text(text("no_data"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		} else {
			items(
				count = subjectRow.scheduleRows.size,
				key = { index -> subjectRow.scheduleRows[index].stableKey },
				contentType = { "grade-schedule" },
			) { index ->
				val schedule = subjectRow.scheduleRows[index]
				Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) {
					Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(schedule.type)
						Text(schedule.markMax)
					}
				}
			}
		}
		item(key = "subject-detail:calculator", contentType = "subject-detail-action") {
			Button(onClick = { navigator.go(if (!subjectRow.hasSchedules) AppRoute.CalculatorMissing else AppRoute.Calculator) }, modifier = Modifier.fillMaxWidth()) {
				Text(calculationTitle)
			}
		}
	}
}

@Composable
internal fun CalculatorScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, missing: Boolean) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	val subject = remember(viewModel.gradesState?.readyValue(), selectedSubject) {
		findGradeSubject(viewModel.gradesState.readyValue()?.subjects.orEmpty(), selectedSubject)
	}
	val components = remember(subject) {
		listOf(
			GradeComponent(org.materialclass.grades.GradeCategory.FO, subject?.quarter1?.toDoubleOrNull()),
			GradeComponent(org.materialclass.grades.GradeCategory.SOR, subject?.quarter2?.toDoubleOrNull()),
			GradeComponent(org.materialclass.grades.GradeCategory.SOCH, subject?.quarter3?.toDoubleOrNull()),
		)
	}
	val estimate = remember(components) { GradeCalculator.calculate(components) }
	val noData = text("no_data")
	val calculationEstimate = text("calculation_estimate")
	val calculationDisclaimer = text("calculation_disclaimer")
	val calculationStatus = text(if (estimate.missingCategories.isEmpty()) "calculation_complete" else "calculation_missing")
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		item(key = "calculator:estimate", contentType = "calculator-summary") {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(calculationEstimate, style = MaterialTheme.typography.labelLarge)
				Text(if (estimate.percentage == null) noData else "${estimate.percentage.toInt()}%", style = MaterialTheme.typography.displaySmall)
				Text(calculationDisclaimer, color = MaterialTheme.colorScheme.onTertiaryContainer)
			}
		}
		}
		item(key = "calculator:status", contentType = "calculator-status") {
			Text(calculationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		if (missing || subject?.schedules.isNullOrEmpty()) {
			item(key = "calculator:missing", contentType = "calculator-warning") {
				Card(shape = RowShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
					Text(text("calculator_missing"), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onErrorContainer)
				}
			}
		}
	}
}
