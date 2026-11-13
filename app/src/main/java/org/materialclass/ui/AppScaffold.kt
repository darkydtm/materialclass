package org.materialclass.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScaffold(
	navigator: AppNavigator,
	text: (String) -> String,
	viewModel: SchoolViewModel,
	snackbarHostState: SnackbarHostState,
	language: AppLanguage,
	theme: ThemeMode,
	selectedSubject: String,
	onSubjectSelected: (String) -> Unit,
	selectedLesson: String,
	onLessonSelected: (String) -> Unit,
	onLanguage: (AppLanguage) -> Unit,
	onTheme: (ThemeMode) -> Unit,
	newGradeNotifications: Boolean,
	onNewGradeNotifications: (Boolean) -> Unit,
	homeworkNotifications: Boolean,
	onHomeworkNotifications: (Boolean) -> Unit,
	backgroundWork: Boolean,
	onBackgroundWork: (Boolean) -> Unit,
	backgroundInterval: Float,
	onBackgroundInterval: (Float) -> Unit,
	onLogout: () -> Unit,
) {
	val rootRoutes = listOf(AppRoute.Home, AppRoute.Diary, AppRoute.Schedule, AppRoute.Settings)
	Scaffold(
		topBar = {
			CenterAlignedTopAppBar(
				colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
				title = { Text(text("app_name")) },
				navigationIcon = {
					if (navigator.current !in ROOT_ROUTES) {
						IconButton(onClick = { navigator.back() }, modifier = Modifier.testTag("back")) {
							Icon(Icons.Default.ArrowBack, contentDescription = text("back"))
						}
					}
				},
				actions = {
					if (navigator.current in ROOT_ROUTES) {
						IconButton(onClick = { navigator.go(AppRoute.Profile) }) {
							Icon(Icons.Default.AccountCircle, contentDescription = text("profile"))
						}
					}
				},
			)
		},
		bottomBar = {
			if (navigator.current in ROOT_ROUTES) {
				NavigationBar(
					modifier = Modifier.navigationBarsPadding(),
					containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
					tonalElevation = 0.dp,
				) {
					ROOT_ROUTES.forEach { route ->
						NavigationBarItem(
							selected = navigator.current == route,
							onClick = { navigator.go(route) },
							icon = { Icon(route.icon(), contentDescription = null) },
							label = { Text(text(route.labelKey())) },
							colors = NavigationBarItemDefaults.colors(
								selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
								selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
								indicatorColor = MaterialTheme.colorScheme.primaryContainer,
								unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
								unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
							),
						)
					}
				}
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { padding ->
		when (navigator.current) {
			AppRoute.Home -> HomeScreen(padding, text, viewModel, navigator, onLessonSelected)
			AppRoute.HomeError -> StateScreen(padding, text, "nav_home", "error_load", "error_load_body") { viewModel.retry(AppRoute.HomeError) }
			AppRoute.HomeEmpty -> StateScreen(padding, text, "nav_home", "empty_day", "empty_day_body") { navigator.go(AppRoute.Schedule) }
			AppRoute.Schedule -> ScheduleScreen(padding, text, viewModel, navigator, onLessonSelected)
			AppRoute.ScheduleOffline -> StateScreen(padding, text, "week_title", "offline_title", "offline_body") { viewModel.retry(AppRoute.Schedule) }
			AppRoute.LessonDetail -> LessonDetailScreen(padding, text, viewModel, selectedLesson)
			AppRoute.Diary -> DiaryScreen(padding, text, viewModel, navigator, onSubjectSelected)
			AppRoute.SubjectDetail -> SubjectDetailScreen(padding, text, viewModel, selectedSubject, navigator)
			AppRoute.Calculator -> CalculatorScreen(padding, text, viewModel, selectedSubject, false)
			AppRoute.CalculatorMissing -> CalculatorScreen(padding, text, viewModel, selectedSubject, true)
			AppRoute.Settings -> SettingsScreen(
				padding,
				text,
				navigator,
				language = language,
				theme = theme,
				newGradeNotifications = newGradeNotifications,
				homeworkNotifications = homeworkNotifications,
				backgroundWork = backgroundWork,
			)
			AppRoute.SettingsLanguage -> ChoiceScreen(padding, text, "language", AppLanguage.entries, onLanguage) { text("language_${it.tag}") }
			AppRoute.SettingsTheme -> ChoiceScreen(padding, text, "theme", ThemeMode.entries, onTheme) { mode ->
				val key = if (mode == ThemeMode.BLACK) "theme_amoled" else "theme_${mode.name.lowercase(Locale.ROOT)}"
				text(key)
			}
			AppRoute.SettingsNotifications -> NotificationsScreen(padding, text, newGradeNotifications, onNewGradeNotifications, homeworkNotifications, onHomeworkNotifications)
			AppRoute.BackgroundWork -> BackgroundWorkScreen(padding, text, backgroundWork, onBackgroundWork, backgroundInterval, onBackgroundInterval)
			AppRoute.Profile -> ProfileScreen(padding, text, viewModel, onLogout)
			else -> Unit
		}
	}
}

private val ROOT_ROUTES = listOf(
	AppRoute.Home,
	AppRoute.Diary,
	AppRoute.Schedule,
	AppRoute.Settings,
)
private fun AppRoute.labelKey(): String = when (this) {
	AppRoute.Home -> "nav_home"
	AppRoute.Diary -> "nav_diary"
	AppRoute.Schedule -> "nav_schedule"
	AppRoute.Settings -> "nav_settings"
	else -> "app_name"
}

private fun AppRoute.icon(): ImageVector = when (this) {
	AppRoute.Home -> Icons.Default.Home
	AppRoute.Diary -> Icons.Default.MenuBook
	AppRoute.Schedule -> Icons.Default.CalendarMonth
	AppRoute.Settings -> Icons.Default.Settings
	else -> Icons.Default.Home
}
