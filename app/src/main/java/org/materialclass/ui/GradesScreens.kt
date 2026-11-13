package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import org.materialclass.data.GradeSubject
import org.materialclass.grades.GradeCalculator
import org.materialclass.grades.GradeCategory
import org.materialclass.grades.GradeComponent

@Composable
internal fun DiaryScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, navigator: AppNavigator, onSubjectSelected: (String) -> Unit) {
	var quarter by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(quarter) { viewModel.loadGrades((quarter + 1).toString()) }
	val grades = viewModel.gradesState.readyValue()
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		item {
			Card(shape = NotificationShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.padding(5.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					(0..3).forEach { index ->
						FilterChip(
							selected = quarter == index,
							onClick = { quarter = index },
							shape = RoundedCornerShape(14.dp),
							label = { Text((index + 1).toString()) },
						)
					}
				}
			}
		}
		if (grades == null) {
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
				SubjectCard(subject, text) {
					subject.subjectName?.let(onSubjectSelected)
					navigator.go(AppRoute.SubjectDetail)
				}
			}
		}
	}
}

@Composable
private fun SubjectCard(subject: GradeSubject, text: (String) -> String, onClick: () -> Unit) {
	Card(onClick = onClick, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(subject.subjectName ?: text("unknown"), style = MaterialTheme.typography.titleLarge)
				Text(subject.finalScore ?: text("no_data"), style = MaterialTheme.typography.titleMedium)
			}
			Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				listOf(subject.quarter1, subject.quarter2, subject.quarter3, subject.quarter4).forEach { score ->
					Text(score ?: text("no_data"), modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

@Composable
internal fun SubjectDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, navigator: AppNavigator) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	val subject = viewModel.gradesState.readyValue()?.subjects?.firstOrNull { it.subjectName == selectedSubject }
	if (subject == null) {
		StateScreen(padding, text, "subject_title", "subject_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.SubjectDetail) }
		return
	}
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Text(subject.subjectName.orEmpty(), style = MaterialTheme.typography.headlineLarge)
		Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("final_grade"), style = MaterialTheme.typography.labelLarge)
				Text(subject.finalScore ?: text("no_data"), style = MaterialTheme.typography.displaySmall)
			}
		}
		Text(text("calculation"), style = MaterialTheme.typography.titleMedium)
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (subject.schedules.isEmpty()) {
				Text(text("no_data"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			subject.schedules.forEach { schedule ->
				Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) {
					Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(schedule.type ?: text("unknown"))
						Text(schedule.markMax?.toString() ?: "-")
					}
				}
			}
		}
		Button(onClick = { navigator.go(if (subject.schedules.isEmpty()) AppRoute.CalculatorMissing else AppRoute.Calculator) }, modifier = Modifier.fillMaxWidth()) {
			Text(text("calculation_title"))
		}
	}
}

@Composable
internal fun CalculatorScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, missing: Boolean) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	val subject = viewModel.gradesState.readyValue()?.subjects?.firstOrNull { it.subjectName == selectedSubject }
	val components = remember(subject) {
		listOf(
			GradeComponent(org.materialclass.grades.GradeCategory.FO, subject?.quarter1?.toDoubleOrNull()),
			GradeComponent(org.materialclass.grades.GradeCategory.SOR, subject?.quarter2?.toDoubleOrNull()),
			GradeComponent(org.materialclass.grades.GradeCategory.SOCH, subject?.quarter3?.toDoubleOrNull()),
		)
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
