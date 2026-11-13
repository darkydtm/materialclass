package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check

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

private val backgroundIntervals = listOf(15, 30, 60, 120, 360)

internal data class ChoiceRow<T>(val key: String, val value: T, val label: String)

internal data class SettingsItem(val key: String, val label: String, val supporting: String)

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
	val rows = remember(language, theme, text) {
		listOf(
			SettingsItem("language", text("language"), text("language_${language.tag}")),
			SettingsItem("theme", text("theme"), text(if (theme == ThemeMode.BLACK) "theme_amoled" else "theme_${theme.name.lowercase(Locale.ROOT)}")),
			SettingsItem("notifications", text("notifications"), text("notifications_description")),
			SettingsItem("background_work", text("background_work"), text("background_work_description")),
		)
	}
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		items(rows, key = { it.key }, contentType = { "setting" }) { row ->
			SettingRow(row.label, row.supporting) {
				navigator.go(
					when (row.key) {
						"language" -> AppRoute.SettingsLanguage
						"theme" -> AppRoute.SettingsTheme
						"notifications" -> AppRoute.SettingsNotifications
						else -> AppRoute.BackgroundWork
					},
				)
			}
		}
	}
}

@Composable
private fun SettingRow(label: String, supporting: String, onClick: () -> Unit) {
	Card(onClick = onClick, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.padding(horizontal = 20.dp).height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(label, style = MaterialTheme.typography.labelLarge)
				Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Icon(Icons.Default.ArrowForward, contentDescription = null)
		}
	}
}

@Composable
internal fun <T> ChoiceScreen(padding: PaddingValues, text: (String) -> String, kind: String, rows: List<ChoiceRow<T>>, onValue: (T) -> Unit) {
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		item(key = "choice:title", contentType = "choice-header") {
			Text(text(kind), style = MaterialTheme.typography.headlineMedium)
		}
		items(rows, key = { row -> row.key }, contentType = { "choice" }) { row ->
			Card(onClick = { onValue(row.value) }, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					Text(row.label)
					Icon(Icons.Default.Check, contentDescription = null)
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
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		item(key = "notifications:description", contentType = "description") {
			Text(text("notifications_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		item(key = "notifications:new-grade", contentType = "notification") {
			NotificationRow(text("notification_new_grade_setting"), text("notification_new_grade_description"), newGradeNotifications, onNewGradeNotifications)
		}
		item(key = "notifications:homework", contentType = "notification") {
			NotificationRow(text("notification_homework_setting"), text("notification_homework_description"), homeworkNotifications, onHomeworkNotifications)
		}
	}
}

@Composable
private fun NotificationRow(label: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
	Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 16.dp, bottom = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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
	val intervalIndex = backgroundIntervals.indexOf(interval.toInt()).coerceAtLeast(0)
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		item(key = "background-work:description", contentType = "description") {
			Text(text("background_work_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		item(key = "background-work:controls", contentType = "background-controls") {
			Card(shape = BackgroundShape, modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
				Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
					Text(text("background_work_enabled"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
					Switch(checked = enabled, onCheckedChange = onEnabled)
				}
				Text(text("update_check_frequency"), style = MaterialTheme.typography.labelLarge)
				Slider(value = intervalIndex.toFloat(), onValueChange = { onInterval(backgroundIntervals[it.toInt()].toFloat()) }, valueRange = 0f..4f, steps = 3, enabled = enabled)
				Text(text("update_interval_${backgroundIntervals[intervalIndex].suffix()}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
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
	val displayName = displayNameFromFullName(profile?.fullName) ?: text("student_name")
	val initials = displayName.firstOrNull()?.toString() ?: text("no_data")
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		item(key = "profile:avatar", contentType = "profile-header") {
			Surface(modifier = Modifier.size(80.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
				BoxCenter { Text(initials, style = MaterialTheme.typography.headlineLarge) }
			}
		}
		item(key = "profile:name", contentType = "profile-header") {
			Text(displayName, style = MaterialTheme.typography.headlineLarge)
			Text(profile?.groupLabel ?: text("student_context"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		item(key = "profile:details", contentType = "profile-details") {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(text("school"), style = MaterialTheme.typography.labelLarge)
					Text(profile?.schoolLabel ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
					profile?.region?.let { Text(it) }
					profile?.groupLabel?.let { DetailRow(text("class_name"), it) }
					profile?.schoolYear?.let { DetailRow(text("school_year"), it.toString()) }
				}
			}
		}
		item(key = "profile:logout", contentType = "profile-action") {
			Button(onClick = onLogout, shape = RowShape, modifier = Modifier.fillMaxWidth()) { Text(text("logout")) }
		}
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
