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

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
	val todayIndex = schedule?.days?.indexOfFirst { it.isCurrent }?.takeIf { it >= 0 } ?: 0
	val today = schedule?.days?.getOrNull(todayIndex)
	val homeState = viewModel.scheduleState.toUiState()
	val completedLessons = remember(today?.lessons) { today?.lessons?.count { it.theme != null } ?: 0 }
	val lessonRows = remember(today?.dateLabel, today?.lessons, todayIndex) { today?.let { scheduleLessonRows(it.lessons, scheduleDayScope(it)) }.orEmpty() }
	val greeting = text("greeting_day")
	val studentName = displayNameFromFullName(profile?.fullName) ?: text("student_name")
	val studentContext = profile?.groupLabel ?: text("student_context")
	val progressLabel = if (today == null) text("no_data") else "$completedLessons / ${today.lessons.size}"
	val lessonsTitle = text("lessons_today")
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(padding).testTag("home-screen"),
		contentPadding = PaddingValues(20.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
	) {
		item(key = "home:greeting", contentType = "home-header") {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(greeting, color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(studentName, style = MaterialTheme.typography.headlineLarge)
				Text(studentContext, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		item(key = "home:progress", contentType = "home-summary") {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(text("day_progress"), style = MaterialTheme.typography.labelLarge)
					Text(progressLabel, style = MaterialTheme.typography.displaySmall)
					if (today != null) LinearProgressIndicator(
						progress = { if (today.lessons.isEmpty()) 0f else completedLessons.toFloat() / today.lessons.size },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		}
		item(key = "home:lessons-title", contentType = "home-header") { Text(lessonsTitle, style = MaterialTheme.typography.titleLarge) }
		if (today == null) {
			item(key = "home:state", contentType = "state") { StatePanel(text, homeState, { viewModel.retry(AppRoute.Schedule) }) }
		} else {
			items(
				count = lessonRows.size,
				key = { index -> lessonRows[index].stableKey },
				contentType = { "lesson" },
			) { index ->
				val lesson = lessonRows[index]
				LessonCard(lesson) {
					onLessonSelected(lesson.stableKey)
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
	val noData = text("no_data")
	val weekPickerLabels = remember(text) { text("previous_week") to text("next_week") }
	val rangeLabel = remember(schedule?.days, noData) { schedule?.days?.let { days ->
		val first = days.firstOrNull()?.dateLabel
		val last = days.lastOrNull()?.dateLabel
		when {
			first == null -> noData
			first == last -> first
			last == null -> first
			else -> "$first - $last"
		}
	} ?: noData }
	val lessonRows = remember(day?.dateLabel, day?.lessons, safeSelectedDay) { day?.let { scheduleLessonRows(it.lessons, scheduleDayScope(it)) }.orEmpty() }
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(padding).testTag("schedule-screen"),
		contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		item(key = "schedule:week-picker", contentType = "schedule-header") {
			Card(shape = WeekPickerShape) {
				Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
						IconButton(onClick = { weekOffset-- }) { Icon(Icons.Default.ArrowBack, contentDescription = weekPickerLabels.first) }
						Text(rangeLabel, style = MaterialTheme.typography.titleMedium)
						IconButton(onClick = { weekOffset++ }) { Icon(Icons.Default.ArrowForward, contentDescription = weekPickerLabels.second) }
					}
					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
						(0..4).forEach { index ->
							val selected = safeSelectedDay == index
							Surface(
								modifier = Modifier.size(width = 54.dp, height = 45.dp).clickable { selectedDay = index },
								shape = WeekPickerShape,
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
		item(key = "schedule:day-title", contentType = "schedule-header") { Text(day?.dateLabel ?: text("no_data"), style = MaterialTheme.typography.titleLarge) }
		if (day == null) {
			item(key = "schedule:state", contentType = "state") { StatePanel(text, viewModel.scheduleState.toUiState(), { viewModel.retry(AppRoute.Schedule) }) }
		} else if (day.lessons.isEmpty()) {
			item(key = "schedule:empty", contentType = "state") { StatePanel(text, UiContent.Empty) { navigator.go(AppRoute.HomeEmpty) } }
		} else {
			items(
				count = lessonRows.size,
				key = { index -> lessonRows[index].stableKey },
				contentType = { "lesson" },
			) { index ->
				val lesson = lessonRows[index]
				LessonCard(lesson) {
					onLessonSelected(lesson.stableKey)
					navigator.go(AppRoute.LessonDetail)
				}
			}
		}
	}
}

@Composable
private fun LessonCard(lesson: LessonRow, onClick: () -> Unit) {
	Card(onClick = onClick, shape = LessonShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(lesson.timeslot, style = MaterialTheme.typography.titleMedium)
				lesson.cabinet?.let { cabinet ->
					Text(cabinet, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(lesson.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
				lesson.type?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
			lesson.homework?.let { homework ->
				Text(homework, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}


@Composable
internal fun LessonDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedLesson: String) {
	val lesson = remember(viewModel.scheduleState?.readyValue(), selectedLesson) {
		findScheduleLesson(viewModel.scheduleState?.readyValue()?.days.orEmpty(), selectedLesson)
	}
	if (lesson == null) {
		StateScreen(padding, text, "lesson_title", "lesson_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.Schedule) }
		return
	}
	val title = lesson.label.orEmpty()
	val theme = lesson.theme ?: text("no_data")
	val homework = lesson.homework ?: text("no_data")
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		item(key = "lesson-detail:title", contentType = "lesson-header") {
			Text(title, style = MaterialTheme.typography.headlineLarge)
		}
		item(key = "lesson-detail:topic", contentType = "lesson-summary") {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text("lesson_topic"), style = MaterialTheme.typography.labelLarge)
				Text(theme, style = MaterialTheme.typography.headlineSmall)
				Text(text("homework"), style = MaterialTheme.typography.labelLarge)
				Text(homework)
			}
			}
		}
		item(key = "lesson-detail:attachments", contentType = "lesson-attachments") {
			Card(shape = CardShape) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("attachments"), style = MaterialTheme.typography.titleMedium)
				Text(text("attachments_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}
}
