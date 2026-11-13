package org.materialclass.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import org.materialclass.api.AppError
import org.materialclass.api.GatewayLoginResult
import org.materialclass.api.LoginCredentials
import org.materialclass.data.DataState
import org.materialclass.data.DateRange
import org.materialclass.data.DiaryModel
import org.materialclass.data.GradeSubject
import org.materialclass.data.GradesModel
import org.materialclass.data.ProfileModel
import org.materialclass.data.ScheduleLesson
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SchoolRepository
import org.materialclass.grades.GradeCalculator
import org.materialclass.grades.GradeComponent
import org.materialclass.ui.theme.MaterialClassTheme

enum class AppRoute {
	Login,
	LoginLoading,
	LoginInvalid,
	LoginTwoFactor,
	LoginConnectionError,
	Home,
	HomeError,
	HomeEmpty,
	Schedule,
	ScheduleOffline,
	LessonDetail,
	Diary,
	SubjectDetail,
	Calculator,
	CalculatorMissing,
	Settings,
	SettingsLanguage,
	SettingsTheme,
	SettingsNotifications,
	BackgroundWork,
	Profile,
}

class AppNavigator(start: AppRoute) {
	private val stack = mutableListOf(start)
	private var route by mutableStateOf(start)
	val current: AppRoute
		get() = route

	fun go(next: AppRoute) {
		if (current != next) {
			stack += next
			route = next
		}
	}

	fun back(): Boolean {
		if (stack.size == 1) return false
		stack.removeAt(stack.lastIndex)
		route = stack.last()
		return true
	}

	fun reset(next: AppRoute) {
		stack.clear()
		stack += next
		route = next
	}
}

enum class AppLanguage(val tag: String) {
	RU("ru"),
	KK("kk"),
	EN("en"),
}

enum class ThemeMode {
	SYSTEM,
	LIGHT,
	DARK,
	BLACK,
}

enum class DataStateKind {
	Loading,
	Ready,
	Empty,
	Offline,
	Stale,
	Error,
	Unsupported,
}

enum class UiContent {
	Loading,
	Ready,
	Empty,
	Offline,
	Stale,
	Error,
	Unsupported,
	;

	companion object {
		fun from(kind: DataStateKind): UiContent = when (kind) {
			DataStateKind.Loading -> Loading
			DataStateKind.Ready -> Ready
			DataStateKind.Empty -> Empty
			DataStateKind.Offline -> Offline
			DataStateKind.Stale -> Stale
			DataStateKind.Error -> Error
			DataStateKind.Unsupported -> Unsupported
		}
	}

	fun toKind(): DataStateKind = when (this) {
		Loading -> DataStateKind.Loading
		Ready -> DataStateKind.Ready
		Empty -> DataStateKind.Empty
		Offline -> DataStateKind.Offline
		Stale -> DataStateKind.Stale
		Error -> DataStateKind.Error
		Unsupported -> DataStateKind.Unsupported
	}
}

class AppPreferences(context: Context) {
	private val preferences: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

	fun language(): AppLanguage = runCatching {
		AppLanguage.valueOf(preferences.getString(LANGUAGE, AppLanguage.RU.name)!!)
	}.getOrDefault(AppLanguage.RU)

	fun theme(): ThemeMode = runCatching {
		ThemeMode.valueOf(preferences.getString(THEME, ThemeMode.SYSTEM.name)!!)
	}.getOrDefault(ThemeMode.SYSTEM)

	fun newGradeNotifications(): Boolean = preferences.getBoolean(NEW_GRADE_NOTIFICATIONS, false)
	fun homeworkNotifications(): Boolean = preferences.getBoolean(HOMEWORK_NOTIFICATIONS, false)
	fun backgroundWork(): Boolean = preferences.getBoolean(BACKGROUND_WORK, false)
	fun backgroundInterval(): Float = preferences.getInt(BACKGROUND_INTERVAL, 30).toFloat()
	fun setLanguage(value: AppLanguage) { preferences.edit().putString(LANGUAGE, value.name).apply() }
	fun setTheme(value: ThemeMode) { preferences.edit().putString(THEME, value.name).apply() }
	fun setNewGradeNotifications(value: Boolean) { preferences.edit().putBoolean(NEW_GRADE_NOTIFICATIONS, value).apply() }
	fun setHomeworkNotifications(value: Boolean) { preferences.edit().putBoolean(HOMEWORK_NOTIFICATIONS, value).apply() }
	fun setBackgroundWork(value: Boolean) { preferences.edit().putBoolean(BACKGROUND_WORK, value).apply() }
	fun setBackgroundInterval(value: Float) { preferences.edit().putInt(BACKGROUND_INTERVAL, value.toInt()).apply() }

	private companion object {
		const val NAME = "materialclass.settings"
		const val LANGUAGE = "language"
		const val THEME = "theme"
		const val NEW_GRADE_NOTIFICATIONS = "notifications.new-grade"
		const val HOMEWORK_NOTIFICATIONS = "notifications.homework"
		const val BACKGROUND_WORK = "background-work.enabled"
		const val BACKGROUND_INTERVAL = "background-work.interval"
	}
}

class SchoolViewModel(
	private val repository: SchoolRepository? = null,
	private val loginHandler: (LoginCredentials) -> GatewayLoginResult = { GatewayLoginResult.Failure(AppError.Network) },
) {
	private val io = Executors.newCachedThreadPool()
	private var loginRequest = 0L
	var loginState: LoginUiState by mutableStateOf(LoginUiState.Ready)
		private set

	var scheduleState: DataState<ScheduleModel>? by mutableStateOf(null)
		private set
	var diaryState: DataState<DiaryModel>? by mutableStateOf(null)
		private set
	var profileState: DataState<ProfileModel>? by mutableStateOf(null)
		private set
	var gradesState: DataState<GradesModel>? by mutableStateOf(null)
		private set

	private val week: DateRange
		get() {
			val today = LocalDate.now()
			val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
			return DateRange(monday, monday.plusDays(6))
		}

	fun login(login: String, password: String) {
		if (login.isBlank() || password.isBlank()) {
			loginState = LoginUiState.InvalidCredentials
			return
		}
		loginState = LoginUiState.Loading
		val request = ++loginRequest
		io.execute {
			val next = when (val result = loginHandler(LoginCredentials(login, password))) {
				is GatewayLoginResult.Success -> LoginUiState.Confirmed
				is GatewayLoginResult.Failure -> when (result.error) {
					AppError.InvalidCredentials -> LoginUiState.InvalidCredentials
					AppError.Network, AppError.Offline, AppError.Timeout -> LoginUiState.ConnectionError
					AppError.UnsupportedContract -> LoginUiState.Unsupported
					else -> LoginUiState.ConnectionError
				}
			}
			android.os.Handler(android.os.Looper.getMainLooper()).post {
				if (request == loginRequest) loginState = next
			}
		}
	}

	fun loadSchedule() {
		val source = repository ?: run { scheduleState = null; return }
		val observation = source.observeSchedule(week)
		observation.observe { scheduleState = it }
		io.execute { source.refreshSchedule(week) }
	}

	fun loadDiary() {
		val source = repository ?: run { diaryState = null; return }
		val observation = source.observeDiary(week)
		observation.observe { diaryState = it }
		io.execute { source.refreshDiary(week) }
	}

	fun loadGrades(period: String = "1") {
		val source = repository ?: run { gradesState = null; return }
		val observation = source.observeGrades(period)
		observation.observe { gradesState = it }
		io.execute { source.refreshGrades(period) }
	}

	fun loadProfile() {
		val source = repository ?: run { profileState = null; return }
		val observation = source.observeProfile()
		observation.observe { profileState = it }
		io.execute { source.refreshProfile() }
	}

	fun retry(route: AppRoute) {
		when (route) {
			AppRoute.HomeError, AppRoute.HomeEmpty -> loadSchedule()
			AppRoute.Schedule, AppRoute.ScheduleOffline -> loadSchedule()
			AppRoute.Diary -> loadGrades()
			AppRoute.Profile -> loadProfile()
			AppRoute.SubjectDetail, AppRoute.Calculator, AppRoute.CalculatorMissing -> loadGrades()
			else -> Unit
		}
	}

	fun logout() {
		loginRequest++
		repository?.logout()
		scheduleState = null
		diaryState = null
		profileState = null
		gradesState = null
	}

	fun resetLogin() {
		loginState = LoginUiState.Ready
	}
}

sealed interface LoginUiState {
	data object Ready : LoginUiState
	data object Loading : LoginUiState
	data object Confirmed : LoginUiState
	data object InvalidCredentials : LoginUiState
	data object ConnectionError : LoginUiState
	data object Unsupported : LoginUiState
}

@Composable
fun MaterialClassApp(
	repository: SchoolRepository? = null,
	preferences: AppPreferences? = null,
	loginHandler: (LoginCredentials) -> GatewayLoginResult = { GatewayLoginResult.Failure(AppError.Network) },
) {
	val context = LocalContext.current
	val actualPreferences = preferences ?: remember(context) { AppPreferences(context) }
	var language by remember(actualPreferences) { mutableStateOf(actualPreferences.language()) }
	var theme by remember(actualPreferences) { mutableStateOf(actualPreferences.theme()) }
	var newGradeNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.newGradeNotifications()) }
	var homeworkNotifications by remember(actualPreferences) { mutableStateOf(actualPreferences.homeworkNotifications()) }
	var backgroundWork by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundWork()) }
	var backgroundInterval by remember(actualPreferences) { mutableStateOf(actualPreferences.backgroundInterval()) }
	val navigator = remember { AppNavigator(AppRoute.Login) }
	val viewModel = remember(repository, loginHandler) { SchoolViewModel(repository, loginHandler) }
	var selectedSubject by rememberSaveable { mutableStateOf("") }
	var selectedLesson by rememberSaveable { mutableStateOf("") }
	val configuration = LocalConfiguration.current
	val localizedContext = remember(language, context, configuration) {
		val localizedConfiguration = Configuration(configuration)
		localizedConfiguration.setLocale(Locale.forLanguageTag(language.tag))
		context.createConfigurationContext(localizedConfiguration)
	}
	val text: (String) -> String = { key ->
		localizedContext.resources.getIdentifier(key, "string", localizedContext.packageName)
			.takeIf { it != 0 }
			?.let(localizedContext::getString)
			?: key
	}
	val snackbarHostState = remember { SnackbarHostState() }

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
					selectedSubject = selectedSubject,
					onSubjectSelected = { selectedSubject = it },
					selectedLesson = selectedLesson,
					onLessonSelected = { selectedLesson = it },
					onLanguage = { language = it; actualPreferences.setLanguage(it) },
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

private val loginRoutes = setOf(
	AppRoute.Login,
	AppRoute.LoginLoading,
	AppRoute.LoginInvalid,
	AppRoute.LoginTwoFactor,
	AppRoute.LoginConnectionError,
)

private fun loginRoute(state: LoginUiState): AppRoute = when (state) {
	LoginUiState.Ready, LoginUiState.Confirmed -> AppRoute.Login
	LoginUiState.Loading -> AppRoute.LoginLoading
	LoginUiState.InvalidCredentials -> AppRoute.LoginInvalid
	LoginUiState.ConnectionError, LoginUiState.Unsupported -> AppRoute.LoginConnectionError
}

@Composable
private fun LoginFlow(text: (String) -> String, viewModel: SchoolViewModel, route: AppRoute, reset: () -> Unit) {
	when (route) {
		AppRoute.Login -> LoginScreen(text, viewModel)
		AppRoute.LoginLoading -> LoginStateScreen(text("login_checking"), text("login_loading"), text, reset)
		AppRoute.LoginInvalid -> LoginStateScreen(text("login_failed"), text("login_invalid"), text, reset)
		AppRoute.LoginTwoFactor -> LoginStateScreen(text("login_2fa"), text("login_2fa_body"), text, reset)
		AppRoute.LoginConnectionError -> LoginStateScreen(text("login_connection_error"), text("login_connection_error_body"), text, reset)
		else -> Unit
	}
}

@Composable
private fun LoginScreen(text: (String) -> String, viewModel: SchoolViewModel) {
	var login by rememberSaveable { mutableStateOf("") }
	var password by rememberSaveable { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("login-screen"),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(text("login"), style = MaterialTheme.typography.headlineSmall)
			Text(text("login_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			OutlinedTextField(
				value = login,
				onValueChange = { login = it },
				label = { Text(text("username")) },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true,
			)
			OutlinedTextField(
				value = password,
				onValueChange = { password = it },
				label = { Text(text("password")) },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true,
				visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
				trailingIcon = {
					IconButton(onClick = { passwordVisible = !passwordVisible }) {
						Icon(
							if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
							contentDescription = text(if (passwordVisible) "login_hide_password" else "login_show_password"),
						)
					}
				},
			)
			when (viewModel.loginState) {
				LoginUiState.InvalidCredentials -> Text(text("login_invalid"), color = MaterialTheme.colorScheme.error)
				LoginUiState.ConnectionError -> Text(text("login_connection_error_body"), color = MaterialTheme.colorScheme.error)
				LoginUiState.Unsupported -> Text(text("unsupported"), color = MaterialTheme.colorScheme.error)
				LoginUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				else -> Unit
			}
			Button(
				onClick = { viewModel.login(login, password) },
				modifier = Modifier.fillMaxWidth().testTag("login-button"),
			) { Text(text("sign_in")) }
			Text(text("login_connection_error_body").substringBefore('.'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun LoginStateScreen(title: String, body: String, text: (String) -> String, reset: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().padding(20.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		val isLoading = title == text("login_checking")
		val isError = title == text("login_failed") || title == text("login_connection_error")
		Card(
			colors = CardDefaults.cardColors(
				containerColor = when {
					isError -> MaterialTheme.colorScheme.errorContainer
					isLoading -> MaterialTheme.colorScheme.tertiaryContainer
					else -> MaterialTheme.colorScheme.secondaryContainer
				},
			),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				if (isLoading) CircularProgressIndicator(modifier = Modifier.size(32.dp))
				else Icon(if (isError) Icons.Default.Error else Icons.Default.Check, contentDescription = null)
				Text(text("login"), style = MaterialTheme.typography.labelLarge)
				Text(title, style = MaterialTheme.typography.headlineMedium)
				Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
				if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				else Button(onClick = reset, modifier = Modifier.fillMaxWidth()) { Text(text(if (isError && title == text("login_connection_error")) "login_try_again" else "login_again")) }
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
	navigator: AppNavigator,
	text: (String) -> String,
	viewModel: SchoolViewModel,
	snackbarHostState: SnackbarHostState,
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
	val titleKey = navigator.current.titleKey()
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(text(titleKey)) },
				navigationIcon = {
					if (navigator.current !in rootRoutes) {
						IconButton(onClick = { navigator.back() }, modifier = Modifier.testTag("back")) {
							Icon(Icons.Default.ArrowBack, contentDescription = text("back"))
						}
					}
				},
				actions = {
					if (navigator.current in rootRoutes) {
						IconButton(onClick = { navigator.go(AppRoute.Profile) }) {
							Icon(Icons.Default.Person, contentDescription = text("profile"))
						}
					}
				},
			)
		},
		bottomBar = {
				if (navigator.current in rootRoutes) {
					NavigationBar(modifier = Modifier.navigationBarsPadding()) {
						rootRoutes.forEach { route ->
							NavigationBarItem(
								selected = navigator.current == route,
								onClick = { navigator.go(route) },
								icon = { Icon(route.icon(), contentDescription = null) },
								label = { Text(text(route.labelKey())) },
							)
						}
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

private fun AppRoute.titleKey(): String = when (this) {
	AppRoute.Login, AppRoute.LoginLoading, AppRoute.LoginInvalid, AppRoute.LoginTwoFactor, AppRoute.LoginConnectionError -> "login"
	AppRoute.Home, AppRoute.HomeError, AppRoute.HomeEmpty -> "nav_home"
	AppRoute.Schedule, AppRoute.ScheduleOffline -> "week_title"
	AppRoute.LessonDetail -> "lesson_title"
	AppRoute.Diary -> "diary_title"
	AppRoute.SubjectDetail -> "subject_title"
	AppRoute.Calculator, AppRoute.CalculatorMissing -> "calculation_title"
	AppRoute.Settings, AppRoute.SettingsLanguage, AppRoute.SettingsTheme, AppRoute.SettingsNotifications, AppRoute.BackgroundWork -> "nav_settings"
	AppRoute.Profile -> "profile"
}

private fun AppRoute.labelKey(): String = when (this) {
	AppRoute.Home -> "nav_home"
	AppRoute.Diary -> "nav_diary"
	AppRoute.Schedule -> "nav_schedule"
	AppRoute.Settings -> "nav_settings"
	else -> titleKey()
}

private fun AppRoute.icon(): ImageVector = when (this) {
	AppRoute.Home -> Icons.Default.Home
	AppRoute.Diary -> Icons.Default.MenuBook
	AppRoute.Schedule -> Icons.Default.CalendarMonth
	AppRoute.Settings -> Icons.Default.Settings
	else -> Icons.Default.Home
}

@Composable
private fun HomeScreen(
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
				Text(profile?.fullName ?: text("student_name"), style = MaterialTheme.typography.headlineLarge)
				Text(profile?.groupLabel ?: text("student_context"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		item {
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
				Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
			items(today.lessons) { lesson ->
				LessonCard(lesson) {
					onLessonSelected(lesson.uuid.orEmpty())
					navigator.go(AppRoute.LessonDetail)
				}
			}
		}
	}
}

@Composable
private fun ScheduleScreen(
	padding: PaddingValues,
	text: (String) -> String,
	viewModel: SchoolViewModel,
	navigator: AppNavigator,
	onLessonSelected: (String) -> Unit,
) {
	var weekOffset by rememberSaveable { mutableIntStateOf(0) }
	var selectedDay by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(weekOffset) { viewModel.loadSchedule() }
	val schedule = viewModel.scheduleState.readyValue()
	val day = schedule?.days?.getOrNull(selectedDay)
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(padding).testTag("schedule-screen"),
		contentPadding = PaddingValues(20.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(text("week_title"), style = MaterialTheme.typography.headlineMedium)
				Text(text("week_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		item {
			Card {
				Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(schedule?.days?.firstOrNull()?.dateLabel ?: text("no_data"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
						IconButton(onClick = { weekOffset-- }) { Icon(Icons.Default.ArrowBack, contentDescription = text("previous_week")) }
						Text(schedule?.days?.firstOrNull()?.dateLabel ?: text("no_data"), style = MaterialTheme.typography.titleMedium)
						IconButton(onClick = { weekOffset++ }) { Icon(Icons.Default.ArrowForward, contentDescription = text("next_week")) }
					}
					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
						(0..4).forEach { index ->
							FilterChip(
								selected = selectedDay == index,
								onClick = { selectedDay = index },
								label = { Text((index + 1).toString()) },
							)
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
			items(day.lessons) { lesson ->
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
	Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(lesson.timeslot ?: "", style = MaterialTheme.typography.titleMedium)
				lesson.cabinet?.let { AssistChip(onClick = {}, label = { Text(it) }) }
			}
			Text(lesson.label ?: "", style = MaterialTheme.typography.headlineSmall)
			lesson.theme?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			lesson.homework?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		}
	}
}

@Composable
private fun LessonDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedLesson: String) {
	val lesson = viewModel.scheduleState.readyValue()?.days.orEmpty().flatMap { it.lessons }.firstOrNull { it.uuid == selectedLesson }
	if (lesson == null) {
		StateScreen(padding, text, "lesson_title", "lesson_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.Schedule) }
		return
	}
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(lesson.label.orEmpty(), style = MaterialTheme.typography.headlineLarge)
			Text(text("lesson_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text("lesson_topic"), style = MaterialTheme.typography.labelLarge)
				Text(lesson.theme ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
				Text(text("homework"), style = MaterialTheme.typography.labelLarge)
				Text(lesson.homework ?: text("no_data"))
			}
		}
		Card {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("attachments"), style = MaterialTheme.typography.titleMedium)
				Text(text("attachments_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

@Composable
private fun DiaryScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, navigator: AppNavigator, onSubjectSelected: (String) -> Unit) {
	var quarter by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(quarter) { viewModel.loadGrades((quarter + 1).toString()) }
	val grades = viewModel.gradesState.readyValue()
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(text("diary_title"), style = MaterialTheme.typography.headlineMedium)
				Text(text("diary_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		item {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
				(0..3).forEach { index ->
					FilterChip(selected = quarter == index, onClick = { quarter = index }, label = { Text((index + 1).toString()) })
				}
			}
		}
		if (grades == null) {
			item { StatePanel(text, viewModel.gradesState.toUiState(), { viewModel.retry(AppRoute.Diary) }) }
		} else if (grades.subjects.isEmpty()) {
			item { StatePanel(text, UiContent.Empty) }
		} else {
			items(grades.subjects) { subject ->
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
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(subject.subjectName ?: text("unknown"), style = MaterialTheme.typography.titleLarge)
				Text(subject.finalScore ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
			}
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				listOf(subject.quarter1, subject.quarter2, subject.quarter3, subject.quarter4).forEach { score ->
					Text(score ?: text("no_data"), modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

@Composable
private fun SubjectDetailScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, navigator: AppNavigator) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	val subject = viewModel.gradesState.readyValue()?.subjects?.firstOrNull { it.subjectName == selectedSubject }
	if (subject == null) {
		StateScreen(padding, text, "subject_title", "subject_detail_unavailable", "unsupported") { viewModel.retry(AppRoute.SubjectDetail) }
		return
	}
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(subject.subjectName.orEmpty(), style = MaterialTheme.typography.headlineLarge)
			Text(text("subject_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
				Card(modifier = Modifier.fillMaxWidth()) {
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
private fun CalculatorScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, selectedSubject: String, missing: Boolean) {
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
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(text("calculation_title"), style = MaterialTheme.typography.headlineMedium)
			Text(text("calculation_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text("calculation_estimate"), style = MaterialTheme.typography.labelLarge)
				Text(if (estimate.percentage == null) text("no_data") else "${estimate.percentage.toInt()}%", style = MaterialTheme.typography.displaySmall)
				Text(text("calculation_disclaimer"), color = MaterialTheme.colorScheme.onTertiaryContainer)
			}
		}
		Text(text(if (estimate.missingCategories.isEmpty()) "calculation_complete" else "calculation_missing"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		if (missing || subject?.schedules.isNullOrEmpty()) {
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
				Text(text("calculator_missing"), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onErrorContainer)
			}
		}
	}
}

@Composable
private fun SettingsScreen(
	padding: PaddingValues,
	text: (String) -> String,
	navigator: AppNavigator,
	backgroundWork: Boolean,
) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(text("nav_settings"), style = MaterialTheme.typography.headlineMedium)
			Text(text("settings_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			SettingRow(text("language"), text("language_description")) { navigator.go(AppRoute.SettingsLanguage) }
			SettingRow(text("theme"), text("theme_description")) { navigator.go(AppRoute.SettingsTheme) }
			SettingRow(text("notifications"), text("notifications_description")) { navigator.go(AppRoute.SettingsNotifications) }
			SettingRow(text("background_work"), text(if (backgroundWork) "notifications_on" else "notifications_off")) { navigator.go(AppRoute.BackgroundWork) }
		}
	}
}

@Composable
private fun SettingRow(label: String, supporting: String, onClick: () -> Unit) {
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(label, style = MaterialTheme.typography.titleMedium)
				Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Icon(Icons.Default.ArrowForward, contentDescription = null)
		}
	}
}

@Composable
private fun <T> ChoiceScreen(padding: PaddingValues, text: (String) -> String, kind: String, values: List<T>, onValue: (T) -> Unit, label: (T) -> String) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(text(kind), style = MaterialTheme.typography.headlineMedium)
		values.forEach { value ->
			Card(onClick = { onValue(value) }, modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					Text(label(value))
					Icon(Icons.Default.Check, contentDescription = null)
				}
			}
		}
	}
}

@Composable
private fun NotificationsScreen(
	padding: PaddingValues,
	text: (String) -> String,
	newGradeNotifications: Boolean,
	onNewGradeNotifications: (Boolean) -> Unit,
	homeworkNotifications: Boolean,
	onHomeworkNotifications: (Boolean) -> Unit,
) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(text("notifications_title"), style = MaterialTheme.typography.headlineMedium)
		Text(text("notifications_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		NotificationRow(text("notification_new_grade"), newGradeNotifications, onNewGradeNotifications)
		NotificationRow(text("notification_homework"), homeworkNotifications, onHomeworkNotifications)
	}
}

@Composable
private fun NotificationRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
			Text(label, modifier = Modifier.weight(1f))
			Switch(checked = enabled, onCheckedChange = onChange)
		}
	}
}

@Composable
private fun BackgroundWorkScreen(
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
		Text(text("background_work"), style = MaterialTheme.typography.headlineMedium)
		Card(modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
					Text(text("background_work_enabled"), modifier = Modifier.weight(1f))
					Switch(checked = enabled, onCheckedChange = onEnabled)
				}
				Text(text("update_check_frequency"), style = MaterialTheme.typography.labelLarge)
				Slider(value = intervalIndex.toFloat(), onValueChange = { onInterval(intervals[it.toInt()].toFloat()) }, valueRange = 0f..4f, steps = 3, enabled = enabled)
				Text(text("update_interval_${intervals[intervalIndex].suffix()}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ProfileScreen(padding: PaddingValues, text: (String) -> String, viewModel: SchoolViewModel, onLogout: () -> Unit) {
	LaunchedEffect(Unit) { viewModel.loadProfile() }
	val profile = viewModel.profileState.readyValue()
	Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		Surface(modifier = Modifier.size(80.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
			BoxCenter { Text(profile?.fullName?.firstOrNull()?.toString() ?: text("no_data"), style = MaterialTheme.typography.headlineLarge) }
		}
		Text(profile?.fullName ?: text("student_name"), style = MaterialTheme.typography.headlineMedium)
		Text(profile?.groupLabel ?: text("student_context"), color = MaterialTheme.colorScheme.onSurfaceVariant)
		Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text("school"), style = MaterialTheme.typography.labelLarge)
				Text(profile?.schoolLabel ?: text("no_data"), style = MaterialTheme.typography.headlineSmall)
				profile?.region?.let { Text(it) }
				profile?.groupLabel?.let { DetailRow(text("class_name"), it) }
				profile?.schoolYear?.let { DetailRow(text("school_year"), it.toString()) }
			}
		}
		Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text(text("logout")) }
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

@Composable
private fun StateScreen(padding: PaddingValues, text: (String) -> String, bar: String, title: String, body: String, retry: () -> Unit) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Text(text(bar), color = MaterialTheme.colorScheme.onSurfaceVariant)
		Card(colors = CardDefaults.cardColors(containerColor = if (title == text("empty_day")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(text(title), style = MaterialTheme.typography.headlineSmall)
				Text(text(body), color = MaterialTheme.colorScheme.onSurfaceVariant)
				Button(onClick = retry) { Text(text("retry")) }
			}
		}
	}
}

@Composable
private fun StatePanel(text: (String) -> String, state: UiContent, retry: (() -> Unit)? = null) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			when (state) {
				UiContent.Loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
				UiContent.Ready -> Text(text("ready"))
				UiContent.Empty -> Text(text("empty_day"))
				UiContent.Offline -> Text(text("offline_title"))
				UiContent.Stale -> Text(text("offline_title"))
				UiContent.Error -> Text(text("error_load"), color = MaterialTheme.colorScheme.error)
				UiContent.Unsupported -> Text(text("unsupported"), color = MaterialTheme.colorScheme.error)
			}
			if (retry != null && state != UiContent.Ready && state != UiContent.Unsupported) TextButton(onClick = retry) { Text(text("retry")) }
		}
	}
}

private fun <T> DataState<T>?.toUiState(): UiContent = when (this) {
	null -> UiContent.Unsupported
	is DataState.Loading -> UiContent.Loading
	is DataState.Ready -> UiContent.Ready
	is DataState.Empty -> UiContent.Empty
	is DataState.Offline -> if (snapshot == null) UiContent.Offline else UiContent.Stale
	is DataState.Stale -> UiContent.Stale
	is DataState.Error -> if (error == AppError.UnsupportedContract) UiContent.Unsupported else UiContent.Error
}

private fun <T> DataState<T>?.readyValue(): T? = (this as? DataState.Ready<T>)?.snapshot?.value
