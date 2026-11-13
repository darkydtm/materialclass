package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.materialclass.data.ScheduleLesson

@Composable
internal fun HomeScreen(
	padding: PaddingValues,
	text: (String) -> String,
	viewModel: SchoolViewModel,
	navigator: AppNavigator,
	onLessonSelected: (String) -> Unit,
) {
	LaunchedEffect(Unit) {
		viewModel.loadSchedule()
		viewModel.loadProfile()
	}
	val profile = viewModel.profileState.readyValue()
	val schedule = viewModel.scheduleState.readyValue()
	val today = schedule?.days?.firstOrNull { it.isCurrent } ?: schedule?.days?.firstOrNull()
	val homeState = viewModel.scheduleState.toUiState()
	val completedLessons = today?.lessons?.count { it.theme != null } ?: 0
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(padding).testTag("home-screen"),
		contentPadding = PaddingValues(20.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
	) {
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(text("greeting_day"), color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(displayNameFromFullName(profile?.fullName) ?: text("student_name"), style = MaterialTheme.typography.headlineLarge)
				Text(profile?.groupLabel ?: text("student_context"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		item {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(text("day_progress"), style = MaterialTheme.typography.labelLarge)
					Text(if (today == null) text("no_data") else "$completedLessons / ${today.lessons.size}", style = MaterialTheme.typography.displaySmall)
					if (today != null) LinearProgressIndicator(
						progress = { if (today.lessons.isEmpty()) 0f else completedLessons.toFloat() / today.lessons.size },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		}
		item { Text(text("lessons_today"), style = MaterialTheme.typography.titleLarge) }
		if (today == null) {
			item { StatePanel(text, homeState, { viewModel.retry(AppRoute.Schedule) }) }
		} else {
			items(
				count = today.lessons.size,
				key = { index -> stableItemKey("lesson", index, today.lessons[index].uuid) },
				contentType = { "lesson" },
			) { index ->
				val lesson = today.lessons[index]
				LessonCard(lesson) {
					onLessonSelected(lesson.uuid.orEmpty())
					navigator.go(AppRoute.LessonDetail)
				}
			}
		}
	}
}

@Composable
internal fun ScheduleScreen(
	padding: PaddingValues,
	text: (String) -> String,
	viewModel: SchoolViewModel,
	navigator: AppNavigator,
	onLessonSelected: (String) -> Unit,
) {
	var weekOffset by rememberSaveable { mutableIntStateOf(0) }
	var selectedDay by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(weekOffset) { viewModel.loadSchedule(weekOffset) }
	val schedule = viewModel.scheduleState.readyValue()
	val safeSelectedDay = selectedDay.coerceIn(0, (schedule?.days?.lastIndex ?: 0).coerceAtLeast(0))
	val day = schedule?.days?.getOrNull(safeSelectedDay)
	val rangeLabel = schedule?.days?.mapNotNull { it.dateLabel }?.let { labels ->
		when {
			labels.isEmpty() -> text("no_data")
			labels.size == 1 -> labels.first()
			else -> "${labels.first()} - ${labels.last()}"
		}
	} ?: text("no_data")
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(padding).testTag("schedule-screen"),
		contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		item {
			Card(shape = WeekPickerShape) {
				Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
						IconButton(onClick = { weekOffset-- }) { Icon(Icons.Default.ArrowBack, contentDescription = text("previous_week")) }
						Text(rangeLabel, style = MaterialTheme.typography.titleMedium)
						IconButton(onClick = { weekOffset++ }) { Icon(Icons.Default.ArrowForward, contentDescription = text("next_week")) }
					}
					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
						(0..4).forEach { index ->
							val selected = safeSelectedDay == index
							Surface(
								modifier = Modifier.size(width = 54.dp, height = 45.dp).clickable { selectedDay = index },
								shape = RoundedCornerShape(999.dp),
								color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
								contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
								border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
							) {
								Box(contentAlignment = Alignment.Center) { Text((index + 1).toString()) }
							}
						}
					}
				}
			}
		}
		item { Text(day?.dateLabel ?: text("no_data"), style = MaterialTheme.typography.titleLarge) }
		if (day == null) {
			item { StatePanel(text, viewModel.scheduleState.toUiState(), { viewModel.retry(AppRoute.Schedule) }) }
		} else if (day.lessons.isEmpty()) {
			item { StatePanel(text, UiContent.Empty) { navigator.go(AppRoute.HomeEmpty) } }
		} else {
			items(
				count = day.lessons.size,
				key = { index -> stableItemKey("lesson", index, day.lessons[index].uuid) },
				contentType = { "lesson" },
			) { index ->
				val lesson = day.lessons[index]
				LessonCard(lesson) {
					onLessonSelected(lesson.uuid.orEmpty())
					navigator.go(AppRoute.LessonDetail)
				}
			}
		}
	}
}

@Composable
private fun LessonCard(lesson: ScheduleLesson, onClick: () -> Unit) {
	Card(onClick = onClick, shape = LessonShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(lesson.timeslot ?: "", style = MaterialTheme.typography.titleMedium)
				lesson.cabinet?.let(::formatCabinet)?.takeIf(String::isNotBlank)?.let { cabinet ->
					AssistChip(onClick = {}, label = { Text(cabinet) })
				}
			}
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(lesson.label ?: "", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
				lesson.type?.let { AssistChip(onClick = {}, label = { Text(it) }) }
			}
			lesson.homework?.let {
				Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

private fun formatCabinet(cabinet: String): String = cabinet.trim()
	.takeUnless { it.equals("regular", ignoreCase = true) }
	.orEmpty()

@Composable
internal fun LessonDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedLesson: String) {
	val lesson = viewModel.scheduleState.readyValue()?.days.orEmpty().flatMap { it.lessons }.firstOrNull { it.uuid == selectedLesson }
	if (lesson == null) {
		StateScreen(padding, text, "lesson_title", "lesson_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.Schedule) }
		return
	}
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(lesson.label.orEmpty(), style = MaterialTheme.typography.headlineLarge)
		}
		Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text("lesson_topic"), style = MaterialTheme.typography.labelLarge)
				Text(lesson.theme ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
				Text(text("homework"), style = MaterialTheme.typography.labelLarge)
				Text(lesson.homework ?: text("no_data"))
			}
		}
		Card(shape = CardShape) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("attachments"), style = MaterialTheme.typography.titleMedium)
				Text(text("attachments_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}
