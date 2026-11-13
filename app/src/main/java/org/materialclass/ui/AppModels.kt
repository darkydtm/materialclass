package org.materialclass.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import org.materialclass.api.AppError
import org.materialclass.api.GatewayLoginResult
import org.materialclass.api.LoginCredentials
import org.materialclass.data.DataState
import org.materialclass.data.DateRange
import org.materialclass.data.DiaryModel
import org.materialclass.data.GradeSchedule
import org.materialclass.data.GradeSubject
import org.materialclass.data.GradesModel
import org.materialclass.data.ProfileModel
import org.materialclass.data.ScheduleDay
import org.materialclass.data.ScheduleLesson
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SchoolRepository

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

	fun canGoBack(): Boolean = stack.size > 1

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

internal fun displayNameFromFullName(fullName: String?): String? = fullName
	?.trim()
	?.split(Regex("\\s+"))
	?.getOrNull(1)
	?.takeIf { it.isNotBlank() }
	?.lowercase(Locale.ROOT)
	?.replaceFirstChar { it.titlecase(Locale.ROOT) }

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
		AppLanguage.valueOf(preferences.getString(LANGUAGE, AppLanguage.RU.name) ?: AppLanguage.RU.name)
	}.getOrDefault(AppLanguage.RU)

	fun theme(): ThemeMode = runCatching {
		ThemeMode.valueOf(preferences.getString(THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
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

	private fun week(offset: Int = 0): DateRange {
			val today = LocalDate.now()
			val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
			val shiftedMonday = monday.plusWeeks(offset.toLong())
			return DateRange(shiftedMonday, shiftedMonday.plusDays(6))
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

	fun loadSchedule(weekOffset: Int = 0) {
		val source = repository ?: run { scheduleState = null; return }
		val range = week(weekOffset)
		val observation = source.observeSchedule(range)
		observation.observe { scheduleState = it }
		io.execute { source.refreshSchedule(range) }
	}

	fun loadDiary() {
		val source = repository ?: run { diaryState = null; return }
		val range = week()
		val observation = source.observeDiary(range)
		observation.observe { diaryState = it }
		io.execute { source.refreshDiary(range) }
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
internal fun <T> DataState<T>?.toUiState(): UiContent = when (this) {
	null -> UiContent.Unsupported
	is DataState.Loading -> UiContent.Loading
	is DataState.Ready -> UiContent.Ready
	is DataState.Empty -> UiContent.Empty
	is DataState.Offline -> if (snapshot == null) UiContent.Offline else UiContent.Stale
	is DataState.Stale -> UiContent.Stale
	is DataState.Error -> if (error == AppError.UnsupportedContract) UiContent.Unsupported else UiContent.Error
}

internal fun <T> DataState<T>?.readyValue(): T? = (this as? DataState.Ready<T>)?.snapshot?.value

internal fun scheduleLessonStableKeys(lessons: List<ScheduleLesson>, scope: String = ""): List<String> {
	val keys = uniqueStableKeys("lesson", lessons.map(::scheduleLessonIdentity))
	return keys.map { key -> if (scope.isBlank()) key else "day:$scope:$key" }
}

internal data class LessonRow(
	val stableKey: String,
	val timeslot: String,
	val label: String,
	val cabinet: String?,
	val type: String?,
	val homework: String?,
)

internal fun scheduleLessonRows(lessons: List<ScheduleLesson>, scope: String = ""): List<LessonRow> {
	val keys = scheduleLessonStableKeys(lessons, scope)
	return lessons.mapIndexed { index, lesson ->
		LessonRow(
			stableKey = keys[index],
			timeslot = lesson.timeslot.orEmpty(),
			label = lesson.label.orEmpty(),
			cabinet = lesson.cabinet?.trim()?.takeUnless { it.equals("regular", ignoreCase = true) }?.takeIf(String::isNotBlank),
			type = lesson.type?.takeIf(String::isNotBlank),
			homework = lesson.homework?.takeIf(String::isNotBlank),
		)
	}
}

internal fun scheduleDayScope(day: ScheduleDay): String =
	day.dateLabel?.takeIf(String::isNotBlank)
		?: day.lessons.joinToString(";") { lesson -> scheduleLessonIdentity(lesson) }
			.takeIf(String::isNotBlank)
		?: "empty-day"

internal fun findScheduleLesson(days: List<ScheduleDay>, selected: String): ScheduleLesson? {
	days.forEach { day ->
		val scope = scheduleDayScope(day)
		val keys = scheduleLessonStableKeys(day.lessons, scope)
		for (index in day.lessons.indices) {
			val lesson = day.lessons[index]
			if (lesson.uuid == selected || keys[index] == selected) return lesson
		}
	}
	return null
}

internal fun gradeSubjectStableKeys(subjects: List<GradeSubject>): List<String> =
	uniqueStableKeys("subject", subjects.map(::gradeSubjectIdentity))

internal const val INTERACTIVE_FRAME_BUDGET_MS = 8.3f

internal val interactiveScreenFrameBudgets: Map<AppRoute, Float> by lazy {
	AppRoute.entries.associateWith { INTERACTIVE_FRAME_BUDGET_MS }
}

internal data class SubjectRow(
	val stableKey: String,
	val subjectName: String?,
	val name: String,
	val finalScore: String,
	val quarter1: String,
	val quarter2: String,
	val quarter3: String,
	val quarter4: String,
)

internal fun gradeSubjectRows(subjects: List<GradeSubject>, unknown: String, noData: String): List<SubjectRow> {
	val keys = gradeSubjectStableKeys(subjects)
	return subjects.mapIndexed { index, subject ->
		SubjectRow(
			stableKey = keys[index],
			subjectName = subject.subjectName,
			name = subject.subjectName ?: unknown,
			finalScore = subject.finalScore ?: noData,
			quarter1 = subject.quarter1 ?: noData,
			quarter2 = subject.quarter2 ?: noData,
			quarter3 = subject.quarter3 ?: noData,
			quarter4 = subject.quarter4 ?: noData,
		)
	}
}

internal data class SubjectDetailRow(
	val title: String,
	val finalGrade: String,
	val scheduleRows: List<GradeScheduleRow>,
	val hasSchedules: Boolean,
)

internal fun findGradeSubject(subjects: List<GradeSubject>, selected: String): GradeSubject? {
	val keys = gradeSubjectStableKeys(subjects)
	return subjects.indices.firstOrNull { index ->
		keys[index] == selected || subjects[index].subjectName == selected
	}?.let(subjects::get)
}

internal fun gradeSubjectDetailRow(subject: GradeSubject, unknown: String, noData: String): SubjectDetailRow =
	SubjectDetailRow(
		title = subject.subjectName.orEmpty(),
		finalGrade = subject.finalScore ?: noData,
		scheduleRows = gradeScheduleRows(subject.schedules, unknown),
		hasSchedules = subject.schedules.isNotEmpty(),
	)

internal fun gradeScheduleStableKeys(schedules: List<GradeSchedule>): List<String> =
	uniqueStableKeys("grade", schedules.map(::gradeScheduleIdentity))

internal data class GradeScheduleRow(
	val stableKey: String,
	val type: String,
	val markMax: String,
)

internal fun gradeScheduleRows(schedules: List<GradeSchedule>, unknown: String): List<GradeScheduleRow> {
	val keys = gradeScheduleStableKeys(schedules)
	return schedules.mapIndexed { index, schedule ->
		GradeScheduleRow(
			stableKey = keys[index],
			type = schedule.type ?: unknown,
			markMax = schedule.markMax?.toString() ?: "-",
		)
	}
}

private fun scheduleLessonIdentity(lesson: ScheduleLesson): String =
	lesson.uuid?.takeIf(String::isNotBlank)
		?: listOf(lesson.timeslot, lesson.label, lesson.type, lesson.cabinet, lesson.teacher)
			.joinToString("|") { it.orEmpty() }

private fun gradeSubjectIdentity(subject: GradeSubject): String =
	subject.subjectName?.takeIf(String::isNotBlank)
		?: subject.schedules.joinToString(";") { schedule ->
			schedule.uuid?.takeIf(String::isNotBlank)
				?: listOf(schedule.date, schedule.timeStart, schedule.type).joinToString("|") { it.orEmpty() }
		}

private fun gradeScheduleIdentity(schedule: GradeSchedule): String =
	schedule.uuid?.takeIf(String::isNotBlank)
		?: listOf(schedule.date, schedule.timeStart, schedule.type, schedule.markMax?.toString())
			.joinToString("|") { it.orEmpty() }

private fun uniqueStableKeys(kind: String, identities: List<String>): List<String> {
	val occurrences = HashMap<String, Int>(identities.size)
	return identities.map { identity ->
		val occurrence = occurrences.getOrDefault(identity, 0)
		occurrences[identity] = occurrence + 1
		"$kind:${identity.length}:$identity:$occurrence"
	}
}
