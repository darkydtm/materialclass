package org.materialclass.ui


import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView


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
	onBackgroundSettingsChanged: () -> Unit = {},
) {
	val context = LocalContext.current
	val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
	val requestNotificationPermission = {
		if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}
	val actualPreferences = preferences ?: remember(context) { AppPreferences(context) }
	val configuration = LocalConfiguration.current
	val language = AppLanguage.entries.firstOrNull { it.tag == configuration.locales[0].language } ?: AppLanguage.RU
	var theme by remember(actualPreferences) { mutableStateOf(actualPreferences.theme()) }
	var newGradeNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.newGradeNotifications()) }
	var homeworkNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.homeworkNotifications()) }
	var backgroundWork by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundWork()) }
	var backgroundInterval by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundInterval()) }
	val navigator = remember { AppNavigator(if (startAuthenticated) AppRoute.Home else AppRoute.Login) }
	val viewModel = remember(repository, loginHandler) { SchoolViewModel(repository, loginHandler, actualPreferences.gradesPeriod(), actualPreferences::setGradesPeriod) }
	var selectedSubject by rememberSaveable { mutableStateOf("") }
	var selectedLesson by rememberSaveable { mutableStateOf("") }
	val text: (String) -> String = remember(context, configuration) {
		val values = HashMap<String, String>()
		return@remember { key: String ->
			values.getOrPut(key) {
				context.resources.getIdentifier(key, "string", context.packageName)
					.takeIf { it != 0 }
					?.let(context::getString)
					?: key
			}
		}
	}
	val snackbarHostState = remember { SnackbarHostState() }
	var backLayout by remember { mutableStateOf<PredictiveBackLayout?>(null) }
	val currentRoute = navigator.current
	BackHandler(enabled = currentRoute in primaryRoutes && currentRoute != AppRoute.Home) {
		navigator.handlePrimaryBack()
	}
	PredictiveBackHandler(enabled = currentRoute !in loginRoutes && currentRoute !in primaryRoutes && navigator.canGoBack()) { events ->
		try {
			events.collect { event -> backLayout?.setPredictiveProgress(event.progress) }
			backLayout?.commitPredictiveBack()
		} catch (_: kotlinx.coroutines.CancellationException) {
			backLayout?.cancelPredictiveBack()
		}
	}
	LaunchedEffect(viewModel.loginState) {
		if (viewModel.loginState == LoginUiState.Confirmed) navigator.reset(AppRoute.Home)
		if (viewModel.loginState == LoginUiState.ConnectionError) snackbarHostState.showSnackbar(text("login_connection_error"))
	}

	MaterialClassTheme(theme) {
		val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
		val previousRoute = navigator.previousOrNull()
		val renderRoute: @Composable (AppRoute) -> Unit = { route ->
			if (route in loginRoutes) {
				LoginFlow(text, viewModel, loginRoute(viewModel.loginState)) { viewModel.resetLogin(); navigator.reset(AppRoute.Login) }
			} else {
				AppScaffold(
					navigator = navigator,
					route = route,
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
					onNewGradeNotifications = { newGradeNotifications = it; actualPreferences.setNewGradeNotifications(it); if (it) requestNotificationPermission() },
					homeworkNotifications = homeworkNotifications,
					onHomeworkNotifications = { homeworkNotifications = it; actualPreferences.setHomeworkNotifications(it); if (it) requestNotificationPermission() },
					backgroundWork = backgroundWork,
					onBackgroundWork = { backgroundWork = it; actualPreferences.setBackgroundWork(it); onBackgroundSettingsChanged() },
					backgroundInterval = backgroundInterval,
					onBackgroundInterval = { backgroundInterval = it; actualPreferences.setBackgroundInterval(it); onBackgroundSettingsChanged() },
					onLogout = { viewModel.logout(); navigator.reset(AppRoute.Login) },
				)
			}
		}
		AndroidView(
			modifier = Modifier.fillMaxSize(),
			factory = { PredictiveBackLayout(it).also { backLayout = it } },
			update = { layout ->
				layout.updateLayers(
					backgroundColor = backgroundColor,
					gestureEnabled = currentRoute !in loginRoutes && currentRoute !in primaryRoutes && navigator.canGoBack() && previousRoute != null,
					previousRoute = previousRoute,
					currentRoute = currentRoute,
					previousContent = previousRoute?.let { route -> { renderRoute(route) } },
					currentContent = { renderRoute(currentRoute) },
					onClose = { navigator.back() },
				)
			},
		)
	}
}
