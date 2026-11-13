package org.materialclass.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.concurrent.Executors

import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import org.materialclass.api.AppError
import org.materialclass.api.GatewayLoginResult
import org.materialclass.api.LoginCredentials
import org.materialclass.data.DataState
import org.materialclass.data.DateRange
import org.materialclass.data.DiaryModel
import org.materialclass.data.GradesModel
import org.materialclass.data.HomeworksModel
import org.materialclass.data.HomeModel
import org.materialclass.data.JournalGradesModel
import org.materialclass.data.ProfileModel
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SchoolRepository
import org.materialclass.grades.GradeCalculator
import org.materialclass.grades.GradeComponent
import org.materialclass.ui.theme.MaterialClassTheme

public enum class AppRoute {
	Login,
	Home,
	Schedule,
	LessonDetail,
	Grades,
	Homework,
	Journal,
	Attendance,
	Diary,
	SubjectDetail,
	Calculator,
	Profile,
	Settings,
	SettingsLanguage,
	SettingsTheme,
	SettingsNotifications,
}

public class AppNavigator(start: AppRoute) {
	private val stack = mutableListOf(start)
	private var route by mutableStateOf(start)
	public val current: AppRoute
		get() = route

	public fun go(route: AppRoute) {
		if (current != route) {
			stack += route
			this.route = route
		}
	}

	public fun back(): Boolean {
		if (stack.size == 1) return false
		stack.removeAt(stack.lastIndex)
		route = stack.last()
		return true
	}

	public fun reset(route: AppRoute) {
		stack.clear()
		stack += route
		this.route = route
	}
}

public enum class AppLanguage(val tag: String) {
	RU("ru"),
	KK("kk"),
	EN("en"),
}

public enum class ThemeMode {
	SYSTEM,
	LIGHT,
	DARK,
	BLACK,
}

public enum class DataStateKind {
	Loading,
	Ready,
	Empty,
	Offline,
	Stale,
	Error,
	Unsupported,
}

public enum class UiContent {
	Loading,
	Ready,
	Empty,
	Offline,
	Stale,
	Error,
	Unsupported,
	;

	public companion object {
		public fun from(kind: DataStateKind): UiContent = when (kind) {
			DataStateKind.Loading -> Loading
			DataStateKind.Ready -> Ready
			DataStateKind.Empty -> Empty
			DataStateKind.Offline -> Offline
			DataStateKind.Stale -> Stale
			DataStateKind.Error -> Error
			DataStateKind.Unsupported -> Unsupported
		}
	}
}

public class AppPreferences(context: Context) {
	private val preferences: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

	public fun language(): AppLanguage = runCatching { AppLanguage.valueOf(preferences.getString(LANGUAGE, AppLanguage.RU.name)!!) }.getOrDefault(AppLanguage.RU)
	public fun theme(): ThemeMode = runCatching { ThemeMode.valueOf(preferences.getString(THEME, ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
	public fun notificationsEnabled(): Boolean = preferences.getBoolean(NOTIFICATIONS, false)
	public fun setLanguage(language: AppLanguage) { preferences.edit().putString(LANGUAGE, language.name).apply() }
	public fun setTheme(theme: ThemeMode) { preferences.edit().putString(THEME, theme.name).apply() }
	public fun setNotificationsEnabled(enabled: Boolean) { preferences.edit().putBoolean(NOTIFICATIONS, enabled).apply() }

	private companion object {
		const val NAME = "materialclass.settings"
		const val LANGUAGE = "language"
		const val THEME = "theme"
		const val NOTIFICATIONS = "notifications"
	}
}

public class SchoolViewModel(
	private val repository: SchoolRepository? = null,
	private val loginHandler: (LoginCredentials) -> GatewayLoginResult = { GatewayLoginResult.Failure(AppError.Network) },
) {
	private val io = Executors.newCachedThreadPool()
	private var loginRequest = 0L
	public var loginState: LoginUiState by mutableStateOf(LoginUiState.Ready)
		private set
	public var homeState: DataState<HomeModel>? by mutableStateOf(null)
		private set
	public var scheduleState: DataState<ScheduleModel>? by mutableStateOf(null)
		private set
	public var diaryState: DataState<DiaryModel>? by mutableStateOf(null)
		private set
	public var profileState: DataState<ProfileModel>? by mutableStateOf(null)
		private set
	public var gradesState: DataState<GradesModel>? by mutableStateOf(null)
		private set
	public var homeworksState: DataState<HomeworksModel>? by mutableStateOf(null)
		private set
	public var journalState: DataState<JournalGradesModel>? by mutableStateOf(null)
		private set

	private val week: DateRange
		get() {
			val today = LocalDate.now()
			val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
			return DateRange(monday, monday.plusDays(6))
		}

	public fun login(login: String, password: String) {
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

	public fun loadHome() {
		val source = repository ?: run { homeState = null; return }
		val observation = source.observeHome()
		observation.observe { homeState = it }
		io.execute { source.refreshHome() }
	}

	public fun loadSchedule() {
		val source = repository ?: run { scheduleState = null; return }
		val range = week
		val observation = source.observeSchedule(range)
		observation.observe { scheduleState = it }
		io.execute { source.refreshSchedule(range) }
	}

	public fun loadDiary() {
		val source = repository ?: run { diaryState = null; return }
		val range = week
		val observation = source.observeDiary(range)
		observation.observe { diaryState = it }
		io.execute { source.refreshDiary(range) }
	}

	public fun loadProfile() {
		val source = repository ?: run { profileState = null; return }
		val observation = source.observeProfile()
		observation.observe { profileState = it }
		io.execute { source.refreshProfile() }
	}

	public fun loadGrades(period: String = "1") {
		val source = repository ?: run { gradesState = null; return }
		val observation = source.observeGrades(period)
		observation.observe { gradesState = it }
		io.execute { source.refreshGrades(period) }
	}

	public fun loadHomeworks() {
		val source = repository ?: run { homeworksState = null; return }
		val observation = source.observeHomeworks()
		observation.observe { homeworksState = it }
		io.execute { source.refreshHomeworks() }
	}

	public fun loadJournal() {
		val source = repository ?: run { journalState = null; return }
		val observation = source.observeJournal(week)
		observation.observe { journalState = it }
		io.execute { source.refreshJournal(week) }
	}

	public fun retry(route: AppRoute) {
		when (route) {
			AppRoute.Home -> loadHome()
			AppRoute.Schedule -> loadSchedule()
			AppRoute.Diary -> loadDiary()
			AppRoute.Profile -> loadProfile()
			AppRoute.Grades -> loadGrades()
			else -> Unit
		}
	}

	public fun logout() {
		loginRequest++
		repository?.logout()
		homeState = null
		scheduleState = null
		diaryState = null
		profileState = null
		gradesState = null
		homeworksState = null
		journalState = null
		loginState = LoginUiState.Ready
	}
}

public sealed interface LoginUiState {
	public data object Ready : LoginUiState
	public data object Loading : LoginUiState
	public data object Confirmed : LoginUiState
	public data object InvalidCredentials : LoginUiState
	public data object ConnectionError : LoginUiState
	public data object Unsupported : LoginUiState
}

@Composable
public fun MaterialClassApp(
	repository: SchoolRepository? = null,
	preferences: AppPreferences? = null,
	loginHandler: (LoginCredentials) -> GatewayLoginResult = { GatewayLoginResult.Failure(AppError.Network) },
) {
	val context = LocalContext.current
	val actualPreferences = preferences ?: remember(context) { AppPreferences(context) }
	var language by remember(actualPreferences) { mutableStateOf(actualPreferences.language()) }
	var theme by remember(actualPreferences) { mutableStateOf(actualPreferences.theme()) }
	var notifications by remember(actualPreferences) { mutableStateOf(actualPreferences.notificationsEnabled()) }
	val navigator = remember { AppNavigator(AppRoute.Login) }
	val viewModel = remember(repository, loginHandler) { SchoolViewModel(repository, loginHandler) }
	val configuration = LocalConfiguration.current
	val localizedContext = remember(language, context, configuration) {
		val localizedConfiguration = Configuration(configuration)
		localizedConfiguration.setLocale(Locale.forLanguageTag(language.tag))
		context.createConfigurationContext(localizedConfiguration)
	}
	val snackbarHostState = remember { SnackbarHostState() }
	val title = { key: String -> localizedContext.getString(localizedContext.resources.getIdentifier(key, "string", localizedContext.packageName)) }

	LaunchedEffect(viewModel.loginState) {
		if (viewModel.loginState is LoginUiState.ConnectionError) snackbarHostState.showSnackbar(title("connection_error"))
	}
	MaterialClassTheme(theme) {
		Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
			if (navigator.current == AppRoute.Login) {
				LoginScreen(title, viewModel) { navigator.reset(AppRoute.Home) }
			} else {
				AppScaffold(
					navigator = navigator,
					title = title,
					viewModel = viewModel,
					snackbarHostState = snackbarHostState,
					onLanguage = { language = it; actualPreferences.setLanguage(it) },
					onTheme = { theme = it; actualPreferences.setTheme(it) },
					onNotifications = { notifications = it; actualPreferences.setNotificationsEnabled(it) },
					notifications = notifications,
					onLogout = { viewModel.logout(); navigator.reset(AppRoute.Login) },
				)
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppScaffold(
	navigator: AppNavigator,
	title: (String) -> String,
	viewModel: SchoolViewModel,
	snackbarHostState: SnackbarHostState,
	onLanguage: (AppLanguage) -> Unit,
	onTheme: (ThemeMode) -> Unit,
	onNotifications: (Boolean) -> Unit,
	notifications: Boolean,
	onLogout: () -> Unit,
) {
	val rootRoutes = listOf(AppRoute.Home, AppRoute.Diary, AppRoute.Schedule, AppRoute.Homework, AppRoute.Journal, AppRoute.Settings)
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(title(navigator.current.titleKey())) },
				navigationIcon = {
					if (navigator.current !in rootRoutes) {
						IconButton(onClick = { navigator.back() }, modifier = Modifier.testTag("back")) {
							Icon(Icons.Default.ArrowBack, contentDescription = title("back"))
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
							label = { Text(title(route.labelKey())) },
						)
					}
				}
			}
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { padding ->
		when (navigator.current) {
			AppRoute.Home -> HomeScreen(padding, title, viewModel, { viewModel.retry(AppRoute.Home) }, { navigator.go(AppRoute.Schedule) })
			AppRoute.Schedule -> ScheduleScreen(padding, title, viewModel, { navigator.go(AppRoute.LessonDetail) })
			AppRoute.Grades -> GradesScreen(padding, title, viewModel)
			AppRoute.Homework -> HomeworkScreen(padding, title, viewModel)
			AppRoute.Journal -> JournalScreen(padding, title, viewModel)
			AppRoute.Attendance -> UnsupportedScreen(padding, title, "unsupported")
			AppRoute.LessonDetail -> UnsupportedScreen(padding, title, "lesson_detail_unavailable")
			AppRoute.Diary -> DiaryScreen(padding, title, viewModel, { navigator.go(AppRoute.SubjectDetail) })
			AppRoute.SubjectDetail -> UnsupportedScreen(padding, title, "subject_detail_unavailable")
			AppRoute.Calculator -> CalculatorScreen(padding, title)
			AppRoute.Profile -> ProfileScreen(padding, title, viewModel)
			AppRoute.Settings -> SettingsScreen(padding, title, navigator, onLogout)
			AppRoute.SettingsLanguage -> LanguageScreen(padding, title, onLanguage)
			AppRoute.SettingsTheme -> ThemeScreen(padding, title, onTheme)
			AppRoute.SettingsNotifications -> NotificationSettingsScreen(padding, title, notifications, onNotifications)
			AppRoute.Login -> Unit
		}
	}
}

private fun AppRoute.titleKey(): String = when (this) {
	AppRoute.Login -> "app_name"
	AppRoute.Home -> "home"
	AppRoute.Schedule -> "schedule"
	AppRoute.LessonDetail -> "lesson_detail"
	AppRoute.Grades -> "grades"
	AppRoute.Homework -> "homework"
	AppRoute.Journal -> "journal"
	AppRoute.Attendance -> "attendance"
	AppRoute.Diary -> "diary"
	AppRoute.SubjectDetail -> "subject_detail"
	AppRoute.Calculator -> "calculator"
	AppRoute.Profile -> "profile"
	AppRoute.Settings, AppRoute.SettingsLanguage, AppRoute.SettingsTheme, AppRoute.SettingsNotifications -> "settings"
}

private fun AppRoute.labelKey(): String = when (this) {
	AppRoute.Home -> "home"
	AppRoute.Schedule -> "schedule"
	AppRoute.Diary -> "diary"
	AppRoute.Homework -> "homework"
	AppRoute.Journal -> "journal"
	AppRoute.Settings -> "settings"
	else -> titleKey()
}

private fun AppRoute.icon(): ImageVector = when (this) {
	AppRoute.Home -> Icons.Default.Home
	AppRoute.Schedule -> Icons.Default.CalendarMonth
	AppRoute.Diary -> Icons.Default.MenuBook
	AppRoute.Homework -> Icons.Default.Home
	AppRoute.Journal -> Icons.Default.MenuBook
	else -> Icons.Default.Settings
}

@Composable
private fun LoginScreen(title: (String) -> String, viewModel: SchoolViewModel, onConfirmed: () -> Unit) {
	var login by rememberSaveable { mutableStateOf("") }
	var password by rememberSaveable { mutableStateOf("") }
	Column(
		modifier = Modifier.fillMaxSize().padding(24.dp).testTag("login-screen"),
		verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
	) {
		Text(title("app_name"), style = MaterialTheme.typography.headlineLarge)
		Text(title("login_prompt"), style = MaterialTheme.typography.bodyLarge)
		OutlinedTextField(login, { login = it }, label = { Text(title("login")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
		OutlinedTextField(password, { password = it }, label = { Text(title("password")) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
		when (val state = viewModel.loginState) {
			LoginUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
			LoginUiState.InvalidCredentials -> Text(title("invalid_credentials"), color = MaterialTheme.colorScheme.error)
			LoginUiState.ConnectionError -> Text(title("connection_error"), color = MaterialTheme.colorScheme.error)
			LoginUiState.Unsupported -> Text(title("unsupported"), color = MaterialTheme.colorScheme.error)
			else -> Unit
		}
		Button(
			onClick = { viewModel.login(login, password) },
			modifier = Modifier.fillMaxWidth().testTag("login-button"),
		) { Text(title("login_action")) }
		LaunchedEffect(viewModel.loginState) {
			if (viewModel.loginState == LoginUiState.Confirmed) onConfirmed()
		}
	}
}

@Composable
private fun HomeScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel, retry: () -> Unit, openSchedule: () -> Unit) {
	LaunchedEffect(Unit) { viewModel.loadHome() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("home-screen"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title("home"), style = MaterialTheme.typography.headlineMedium)
			Text(title("home_description"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		StatePanel(title, viewModel.homeState.toUiState(), retry)
		Button(onClick = openSchedule, modifier = Modifier.fillMaxWidth()) { Text(title("open_schedule")) }
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel, openDetail: () -> Unit) {
	var weekOffset by rememberSaveable { mutableIntStateOf(0) }
	var day by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(weekOffset) { viewModel.loadSchedule() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("schedule-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			IconButton(onClick = { weekOffset-- }) { Icon(Icons.Default.ArrowBack, contentDescription = title("previous_week")) }
			Text(title("week_title"), style = MaterialTheme.typography.titleLarge)
			IconButton(onClick = { weekOffset++ }) { Icon(Icons.Default.ArrowForward, contentDescription = title("next_week"), modifier = Modifier.size(24.dp)) }
		}
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			(0..4).forEach { index ->
				AssistChip(onClick = { day = index }, label = { Text((index + 1).toString()) }, leadingIcon = if (day == index) ({ Icon(Icons.Default.CalendarMonth, null) }) else null)
			}
		}
		Text(title("selected_day") + ": " + (day + 1), style = MaterialTheme.typography.titleMedium)
		StatePanel(title, viewModel.scheduleState.toUiState(), { viewModel.retry(AppRoute.Schedule) })
		viewModel.scheduleState?.readyValue()?.days?.getOrNull(day)?.lessons?.forEach { lesson -> LessonCard(lesson, openDetail) }
		if (viewModel.scheduleState is DataState.Ready) Button(onClick = openDetail, modifier = Modifier.fillMaxWidth()) { Text(title("open_lesson")) }
	}
}

@Composable
private fun DiaryScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel, openSubject: () -> Unit) {
	var weekOffset by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(weekOffset) { viewModel.loadDiary() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("diary-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(onClick = { weekOffset-- }) { Text(title("previous_week")) }
			OutlinedButton(onClick = { weekOffset++ }) { Text(title("next_week")) }
		}
		StatePanel(title, viewModel.diaryState.toUiState(), { viewModel.retry(AppRoute.Diary) })
		viewModel.diaryState?.readyValue()?.days?.forEach { diaryDay ->
			Card(modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					if (listOfNotNull(diaryDay.dayLabel, diaryDay.dateLabel).isNotEmpty()) Text(listOfNotNull(diaryDay.dayLabel, diaryDay.dateLabel).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
					diaryDay.subjects.forEach { subject ->
						subject.label?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
						listOfNotNull(subject.timeslot, subject.theme, subject.homework).takeIf { it.isNotEmpty() }?.let { details ->
							Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
			}
		}
		if (viewModel.diaryState is DataState.Ready) Button(onClick = openSubject, modifier = Modifier.fillMaxWidth()) { Text(title("open_subject")) }
	}
}

@Composable
private fun CalculatorScreen(padding: PaddingValues, title: (String) -> String) {
	val estimate = remember { GradeCalculator.calculate(emptyList<GradeComponent>()) }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("calculator-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Text(title("calculation_title"), style = MaterialTheme.typography.headlineMedium)
		Text(title("calculation_description"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(title("calculation_estimate"), style = MaterialTheme.typography.labelLarge)
				Text(if (estimate.percentage == null) title("no_calculation") else estimate.percentage.toString(), style = MaterialTheme.typography.headlineSmall)
			}
		}
		if (estimate.missingCategories.isNotEmpty()) Text(title("calculator_missing"), color = MaterialTheme.colorScheme.error)
	}
}

@Composable
private fun GradesScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel) {
	LaunchedEffect(Unit) { viewModel.loadGrades() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Text(title("diary_title"), style = MaterialTheme.typography.headlineMedium)
		StatePanel(title, viewModel.gradesState.toUiState(), { viewModel.loadGrades() })
		viewModel.gradesState?.readyValue()?.subjects?.forEach { subject ->
			Card(modifier = Modifier.fillMaxWidth()) {
				Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
					subject.subjectName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
					Text(subject.finalScore ?: "-", style = MaterialTheme.typography.headlineSmall)
				}
			}
		}
	}
}

@Composable
private fun HomeworkScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel) {
	LaunchedEffect(Unit) { viewModel.loadHomeworks() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(title("homework"), style = MaterialTheme.typography.headlineMedium)
		StatePanel(title, viewModel.homeworksState.toUiState(), { viewModel.loadHomeworks() })
		viewModel.homeworksState?.readyValue()?.items?.forEach { item ->
			Card(modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(item.subjectName ?: title("unknown"), style = MaterialTheme.typography.titleMedium)
					item.theme?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
					item.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
					item.date?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
				}
			}
		}
	}
}

@Composable
private fun JournalScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel) {
	LaunchedEffect(Unit) { viewModel.loadJournal() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(title("journal"), style = MaterialTheme.typography.headlineMedium)
		StatePanel(title, viewModel.journalState.toUiState(), { viewModel.loadJournal() })
		viewModel.journalState?.readyValue()?.grades?.forEach { grade ->
			Card(modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(grade.scheduleUuid ?: title("unknown"), style = MaterialTheme.typography.titleMedium)
					grade.mark?.let { Text("$it/${grade.markMax ?: "?"}") }
					grade.attendance?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
				}
			}
		}
	}
}

@Composable
private fun LessonCard(lesson: org.materialclass.data.ScheduleLesson, onClick: () -> Unit) {
	val label = lesson.label ?: return
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
		Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(label, style = MaterialTheme.typography.titleMedium)
			listOfNotNull(lesson.timeslot, lesson.cabinet, lesson.teacher).takeIf { it.isNotEmpty() }?.let { details ->
				Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			lesson.homework?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
		}
	}
}

@Composable
private fun ProfileScreen(padding: PaddingValues, title: (String) -> String, viewModel: SchoolViewModel) {
	LaunchedEffect(Unit) { viewModel.loadProfile() }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag("profile-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		StatePanel(title, viewModel.profileState.toUiState(), { viewModel.retry(AppRoute.Profile) })
		viewModel.profileState?.readyValue()?.let { profile ->
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					profile.fullName?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
					listOfNotNull(profile.groupLabel, profile.schoolLabel, profile.region).takeIf { it.isNotEmpty() }?.let { details ->
						Text(details.joinToString("\n"), style = MaterialTheme.typography.bodyLarge)
					}
				}
			}
		}
	}
}

@Composable
private fun SettingsScreen(padding: PaddingValues, title: (String) -> String, navigator: AppNavigator, onLogout: () -> Unit) {
	LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		item { SettingsButton(title("language"), title("settings_language")) { navigator.go(AppRoute.SettingsLanguage) } }
		item { SettingsButton(title("theme"), title("settings_theme")) { navigator.go(AppRoute.SettingsTheme) } }
		item { SettingsButton(title("notifications"), title("settings_notifications")) { navigator.go(AppRoute.SettingsNotifications) } }
		item { SettingsButton(title("profile"), title("profile")) { navigator.go(AppRoute.Profile) } }
		item { TextButton(onClick = onLogout) { Text(title("logout")) } }
	}
}

@Composable
private fun SettingsButton(label: String, supporting: String, onClick: () -> Unit) {
	OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.fillMaxWidth()) { Text(label); Text(supporting, style = MaterialTheme.typography.bodySmall) }
	}
}

@Composable
private fun LanguageScreen(padding: PaddingValues, title: (String) -> String, onLanguage: (AppLanguage) -> Unit) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		AppLanguage.entries.forEach { language -> Button(onClick = { onLanguage(language) }, modifier = Modifier.fillMaxWidth()) { Text(language.name) } }
	}
}

@Composable
private fun ThemeScreen(padding: PaddingValues, title: (String) -> String, onTheme: (ThemeMode) -> Unit) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		ThemeMode.entries.forEach { theme -> Button(onClick = { onTheme(theme) }, modifier = Modifier.fillMaxWidth()) { Text(theme.name) } }
	}
}

@Composable
private fun NotificationSettingsScreen(padding: PaddingValues, title: (String) -> String, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
	Row(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
		Text(title("notifications_local"))
		Switch(checked = enabled, onCheckedChange = onEnabled)
	}
}

@Composable
private fun UnsupportedScreen(padding: PaddingValues, title: (String) -> String, key: String) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("unsupported-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(title(key), color = MaterialTheme.colorScheme.error)
		Text(title("unsupported"))
	}
}

@Composable
private fun StatePanel(title: (String) -> String, state: UiContent, retry: () -> Unit) {
	Column(modifier = Modifier.fillMaxWidth().testTag("state-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		when (state) {
			UiContent.Loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
			UiContent.Ready -> Text(title("ready"))
			UiContent.Empty -> Text(title("empty"))
			UiContent.Offline -> { Text(title("offline")); TextButton(onClick = retry) { Text("↻ "); Text(title("retry")) } }
			UiContent.Stale -> { Text(title("stale")); TextButton(onClick = retry) { Text(title("retry")) } }
			UiContent.Error -> { Text(title("error"), color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text(title("retry")) } }
			UiContent.Unsupported -> Text(title("unsupported"), color = MaterialTheme.colorScheme.error)
		}
	}
}

private fun <T> DataState<T>?.toUiState(): UiContent = when (this) {
	null -> UiContent.Unsupported
	is DataState.Loading -> UiContent.Loading
	is DataState.Ready -> UiContent.Ready
	is DataState.Empty -> UiContent.Empty
	is DataState.Offline -> UiContent.Offline
	is DataState.Stale -> UiContent.Stale
	is DataState.Error -> if (error == AppError.UnsupportedContract) UiContent.Unsupported else UiContent.Error
}

private fun <T> DataState<T>?.readyValue(): T? = (this as? DataState.Ready<T>)?.snapshot?.value
