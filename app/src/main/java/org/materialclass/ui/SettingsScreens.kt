package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun SettingsScreen(
	padding: PaddingValues,
	text: (String) -> String,
	navigator: AppNavigator,
	language: AppLanguage,
	theme: ThemeMode,
	newGradeNotifications: Boolean,
	homeworkNotifications: Boolean,
	backgroundWork: Boolean,
) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			SettingRow(text("language"), text("language_${language.tag}")) { navigator.go(AppRoute.SettingsLanguage) }
			SettingRow(text("theme"), text(if (theme == ThemeMode.BLACK) "theme_amoled" else "theme_${theme.name.lowercase(Locale.ROOT)}")) { navigator.go(AppRoute.SettingsTheme) }
			SettingRow(text("notifications"), text("notifications_description")) { navigator.go(AppRoute.SettingsNotifications) }
			SettingRow(text("background_work"), text("background_work_description")) { navigator.go(AppRoute.BackgroundWork) }
		}
	}
}

@Composable
private fun SettingRow(label: String, supporting: String, onClick: () -> Unit) {
	Card(onClick = onClick, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(label, style = MaterialTheme.typography.labelLarge)
				Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Icon(Icons.Default.ArrowForward, contentDescription = null)
		}
	}
}

@Composable
internal fun <T> ChoiceScreen(padding: PaddingValues, text: (String) -> String, kind: String, values: List<T>, selected: T, onValue: (T) -> Unit, label: (T) -> String) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(text(kind), style = MaterialTheme.typography.headlineMedium)
		values.forEach { value ->
			Card(onClick = { onValue(value) }, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					Text(label(value))
					if (value == selected) Icon(Icons.Default.Check, contentDescription = null)
				}
			}
		}
	}
}

@Composable
internal fun NotificationsScreen(
	padding: PaddingValues,
	text: (String) -> String,
	newGradeNotifications: Boolean,
	onNewGradeNotifications: (Boolean) -> Unit,
	homeworkNotifications: Boolean,
	onHomeworkNotifications: (Boolean) -> Unit,
) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(text("notifications_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		NotificationRow(text, text("notification_new_grade_setting"), text("notification_new_grade_description"), newGradeNotifications, onNewGradeNotifications)
		NotificationRow(text, text("notification_homework_setting"), text("notification_homework_description"), homeworkNotifications, onHomeworkNotifications)
	}
}

@Composable
private fun NotificationRow(text: (String) -> String, label: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
	Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 20.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
				Text(label, style = MaterialTheme.typography.bodyMedium)
				Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Switch(checked = enabled, onCheckedChange = onChange)
		}
	}
}

@Composable
internal fun BackgroundWorkScreen(
	padding: PaddingValues,
	text: (String) -> String,
	enabled: Boolean,
	onEnabled: (Boolean) -> Unit,
	interval: Float,
	onInterval: (Float) -> Unit,
) {
	val intervals = listOf(15, 30, 60, 120, 360)
	val intervalIndex = intervals.indexOf(interval.toInt()).coerceAtLeast(0)
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Text(text("background_work_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		Card(shape = BackgroundShape, modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
				Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
					Text(text("background_work_enabled"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
					Switch(checked = enabled, onCheckedChange = onEnabled)
				}
				Text(text("update_check_frequency"), style = MaterialTheme.typography.labelLarge)
				Slider(value = intervalIndex.toFloat(), onValueChange = { onInterval(intervals[it.toInt()].toFloat()) }, valueRange = 0f..4f, steps = 3, enabled = enabled)
				Text(text("update_interval_${intervals[intervalIndex].suffix()}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

private fun Int.suffix(): String = when (this) {
	15 -> "15m"
	30 -> "30m"
	60 -> "1h"
	120 -> "2h"
	else -> "6h"
}

@Composable
internal fun ProfileScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, onLogout: () -> Unit) {
	LaunchedEffect(Unit) { viewModel.loadProfile() }
	val profile = viewModel.profileState.readyValue()
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		Surface(modifier = Modifier.size(80.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
			BoxCenter { Text(displayNameFromFullName(profile?.fullName)?.firstOrNull()?.toString() ?: text("no_data"), style = MaterialTheme.typography.headlineLarge) }
		}
		Text(displayNameFromFullName(profile?.fullName) ?: text("student_name"), style = MaterialTheme.typography.headlineLarge)
		Text(profile?.groupLabel ?: text("student_context"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text("school"), style = MaterialTheme.typography.labelLarge)
				Text(profile?.schoolLabel ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
				profile?.region?.let { Text(it) }
				profile?.groupLabel?.let { DetailRow(text("class_name"), it) }
				profile?.schoolYear?.let { DetailRow(text("school_year"), it.toString()) }
			}
		}
		Button(onClick = onLogout, shape = RowShape, modifier = Modifier.fillMaxWidth()) { Text(text("logout")) }
	}
}

@Composable
private fun BoxCenter(content: @Composable () -> Unit) {
	androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun DetailRow(label: String, value: String) {
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(value)
	}
}
