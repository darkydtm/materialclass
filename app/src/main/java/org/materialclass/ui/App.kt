package org.materialclass.ui


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

import org.materialclass.api.AppError
import org.materialclass.api.GatewayLoginResult
import org.materialclass.api.LoginCredentials
import org.materialclass.data.SchoolRepository
import org.materialclass.ui.theme.MaterialClassTheme

@Composable
fun MaterialClassApp(
	repository: SchoolRepository? = null,
	preferences: AppPreferences? = null,
	loginHandler: (LoginCredentials) -> GatewayLoginResult = { GatewayLoginResult.Failure(AppError.Network) },
	startAuthenticated: Boolean = false,
) {
	val context = LocalContext.current
	val actualPreferences = preferences ?: remember(context) { AppPreferences(context) }
	val configuration = LocalConfiguration.current
	val language = AppLanguage.entries.firstOrNull { it.tag == configuration.locales[0].language } ?: AppLanguage.RU
	var theme by remember(actualPreferences) { mutableStateOf(actualPreferences.theme()) }
	var newGradeNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.newGradeNotifications()) }
	var homeworkNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.homeworkNotifications()) }
	var backgroundWork by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundWork()) }
	var backgroundInterval by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundInterval()) }
	val navigator = remember { AppNavigator(if (startAuthenticated) AppRoute.Home else AppRoute.Login) }
	val viewModel = remember(repository, loginHandler) { SchoolViewModel(repository, loginHandler) }
	var selectedSubject by rememberSaveable { mutableStateOf("") }
	var selectedLesson by rememberSaveable { mutableStateOf("") }
	val text: (String) -> String = remember(context, configuration) {
		val values = HashMap<String, String>()
		{ key ->
			values.getOrPut(key) {
				context.resources.getIdentifier(key, "string", context.packageName)
					.takeIf { it != 0 }
					?.let(context::getString)
					?: key
			}
		}
	}
	val snackbarHostState = remember { SnackbarHostState() }

	BackHandler(enabled = navigator.current !in loginRoutes && navigator.canGoBack()) {
		navigator.back()
	}

	LaunchedEffect(viewModel.loginState) {
		if (viewModel.loginState == LoginUiState.Confirmed) navigator.reset(AppRoute.Home)
		if (viewModel.loginState == LoginUiState.ConnectionError) snackbarHostState.showSnackbar(text("login_connection_error"))
	}

	MaterialClassTheme(theme) {
		Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
			if (navigator.current in loginRoutes) {
				LoginFlow(text, viewModel, loginRoute(viewModel.loginState)) { viewModel.resetLogin(); navigator.reset(AppRoute.Login) }
			} else {
				AppScaffold(
					navigator = navigator,
					text = text,
					viewModel = viewModel,
					snackbarHostState = snackbarHostState,
					language = language,
					theme = theme,
					selectedSubject = selectedSubject,
					onSubjectSelected = { selectedSubject = it },
					selectedLesson = selectedLesson,
					onLessonSelected = { selectedLesson = it },
					onLanguage = { actualPreferences.setLanguage(it) },
					onTheme = { theme = it; actualPreferences.setTheme(it) },
					newGradeNotifications = newGradeNotifications,
					onNewGradeNotifications = { newGradeNotifications = it; actualPreferences.setNewGradeNotifications(it) },
					homeworkNotifications = homeworkNotifications,
					onHomeworkNotifications = { homeworkNotifications = it; actualPreferences.setHomeworkNotifications(it) },
					backgroundWork = backgroundWork,
					onBackgroundWork = { backgroundWork = it; actualPreferences.setBackgroundWork(it) },
					backgroundInterval = backgroundInterval,
					onBackgroundInterval = { backgroundInterval = it; actualPreferences.setBackgroundInterval(it) },
					onLogout = { viewModel.logout(); navigator.reset(AppRoute.Login) },
				)
			}
		}
	}
}
