package org.materialclass.data

import java.time.Instant
import java.time.LocalDate
import org.materialclass.api.AppError

@JvmInline
value class AccountId(val value: String) : java.io.Serializable {
	init {
		require(value.isNotBlank()) { "AccountId must not be blank" }
	}
}

@JvmInline
value class Generation(val value: Long) {
	init {
		require(value >= 0) { "Generation must not be negative" }
	}
}

data class ActiveSession(
	val accountId: AccountId,
	val generation: Generation,
)

data class DateRange(
	val start: LocalDate,
	val endInclusive: LocalDate,
) {
	init {
		require(start <= endInclusive) { "DateRange must be ordered" }
	}
}

data class Snapshot<T>(
	val accountId: AccountId,
	val generation: Generation,
	val value: T,
	val capturedAt: Instant,
)

sealed interface Outcome<out T> {
	data class Success<T>(val snapshot: Snapshot<T>) : Outcome<T>
	data class Empty<T>(val capturedAt: Instant) : Outcome<T>
	data class Failure<T>(val error: AppError) : Outcome<T>
}

sealed interface DataState<out T> {
	data object Loading : DataState<Nothing>
	data class Ready<T>(val snapshot: Snapshot<T>) : DataState<T>
	data object Empty : DataState<Nothing>
	data class Offline<T>(val snapshot: Snapshot<T>? = null) : DataState<T>
	data class Error<T>(val error: AppError, val snapshot: Snapshot<T>? = null) : DataState<T>
	data class Stale<T>(val snapshot: Snapshot<T>) : DataState<T>
}

typealias Loading = DataState.Loading
typealias UiState<T> = DataState<T>
typealias Ready<T> = DataState.Ready<T>
typealias Offline<T> = DataState.Offline<T>
typealias Error<T> = DataState.Error<T>
typealias Stale<T> = DataState.Stale<T>

fun <T> Outcome<T>.toState(): DataState<T> = when (this) {
	is Outcome.Success -> DataState.Ready(snapshot)
	is Outcome.Empty -> DataState.Empty
	is Outcome.Failure -> when (error) {
		AppError.Offline -> DataState.Offline()
		else -> DataState.Error(error)
	}
}

data object HomeModel : java.io.Serializable

data class DiaryModel(
	val dayCount: Int,
	val rangeLabel: String? = null,
	val days: List<DiaryDay> = emptyList(),
) : java.io.Serializable {
	init {
		require(dayCount >= 0) { "Diary day count must not be negative" }
	}
}

data class DiaryDay(
	val dayLabel: String?,
	val dateLabel: String?,
	val isHoliday: Boolean,
	val subjects: List<DiarySubject>,
) : java.io.Serializable

data class DiarySubject(
	val scheduleUuid: String?,
	val label: String?,
	val type: String?,
	val timeslot: String?,
	val cabinet: String?,
	val theme: String?,
	val teacher: String?,
	val homework: String?,
	val hasFiles: Boolean,
	val hasBooks: Boolean,
) : java.io.Serializable

data class DiaryWeeksModel(
	val weekCount: Int,
	val periodCount: Int,
) {
	init {
		require(weekCount >= 0) { "Diary week count must not be negative" }
		require(periodCount >= 0) { "Diary period count must not be negative" }
	}
}

data class PeriodsModel(
	val periodCount: Int,
	val periods: List<DiaryPeriod> = emptyList(),
) {
	init {
		require(periodCount >= 0) { "Period count must not be negative" }
		require(periods.size <= periodCount) { "Period list must fit the period count" }
}
}

data class DiaryPeriod(
	val period: Int?,
	val start: String?,
	val end: String?,
) : java.io.Serializable

data class SubjectsModel(
	val subjectCount: Int,
	val subjects: List<GradeSubject> = emptyList(),
) {
	init {
		require(subjectCount >= 0) { "Subject count must not be negative" }
		require(subjectCount == subjects.size || subjects.isEmpty()) { "Subject count must match subjects" }
	}
}

data class ScheduleModel(
	val dayCount: Int,
	val daysWithSchedule: Int = 0,
	val days: List<ScheduleDay> = emptyList(),
) : java.io.Serializable {
	init {
		require(dayCount >= 0) { "Schedule day count must not be negative" }
		require(daysWithSchedule in 0..dayCount) { "Scheduled day count must fit the day count" }
	}
}

data class ScheduleDay(
	val dateLabel: String?,
	val isCurrent: Boolean,
	val isHoliday: Boolean,
	val lessons: List<ScheduleLesson>,
) : java.io.Serializable

data class ScheduleLesson(
	val uuid: String?,
	val label: String?,
	val timeslot: String?,
	val cabinet: String?,
	val teacher: String?,
	val type: String?,
	val theme: String?,
	val homework: String?,
) : java.io.Serializable

data object LessonDetailModel

data class GradesModel(
	val subjectCount: Int,
	val subjects: List<GradeSubject> = emptyList(),
) : java.io.Serializable {
	init {
		require(subjectCount >= 0) { "Grade subject count must not be negative" }
	}
}

data class GradeSubject(
	val subjectName: String?,
	val finalScore: String?,
	val schedules: List<GradeSchedule>,
	val quarter1: String? = null,
	val quarter2: String? = null,
	val quarter3: String? = null,
	val quarter4: String? = null,
	val yearScore: String? = null,
	val totalMissCount: Int? = null,
	val scores: List<String> = emptyList(),
	val scoreType: String? = null,
	val scoreEntries: List<GradeScoreEntry> = emptyList(),
) : java.io.Serializable

data class GradeScoreEntry(
	val category: String?,
	val value: String?,
	val max: String? = null,
) : java.io.Serializable

data class JournalGrade(
	val scheduleUuid: String?,
	val attendance: String?,
	val formattedScore: String?,
	val sor: String?,
	val soch: String?,
	val po: String?,
	val mark: String? = null,
	val markMax: String? = null,
	val comment: String? = null,
) : java.io.Serializable

data class JournalGradesModel(
	val grades: List<JournalGrade>,
	val groupsSucceeded: Int,
	val groupsFailed: Int,
) : java.io.Serializable {
	init {
		require(groupsSucceeded >= 0) { "Successful group count must not be negative" }
		require(groupsFailed >= 0) { "Failed group count must not be negative" }
	}
}

data class HomeworkItem(
	val uuid: String?,
	val subjectName: String?,
	val theme: String?,
	val body: String?,
	val date: String?,
	val hasFiles: Boolean?,
) : java.io.Serializable

data class HomeworksModel(val items: List<HomeworkItem>) : java.io.Serializable

data class HomeworkDetailModel(
	val subjectName: String?,
	val groupName: String?,
	val date: String?,
	val dateExpired: String?,
	val themeName: String?,
	val homeworkBody: String?,
	val teacherFio: String?,
)

data class SystemDataModel(
	val dataKeys: Set<String>,
	val itemCount: Int?,
)

data class GradeSchedule(
	val uuid: String?,
	val date: String?,
	val timeStart: String?,
	val markMax: Int?,
	val type: String?,
	val score: String? = null,
	val attendance: String? = null,
) : java.io.Serializable

data object AttendanceModel

data object SubjectDetailsModel

data class ProfileModel(
	val accountId: AccountId,
	val schoolLabel: String?,
	val groupLabel: String?,
	val fullName: String? = null,
	val region: String? = null,
	val schoolYear: Int? = null,
) : java.io.Serializable {
	init {
		require(schoolLabel?.isNotBlank() != false) { "School label must not be blank" }
		require(groupLabel?.isNotBlank() != false) { "Group label must not be blank" }
	}
}

data object NotificationsModel
