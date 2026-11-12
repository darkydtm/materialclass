package org.materialclass.data

import java.time.Instant
import java.time.LocalDate
import org.materialclass.api.AppError

@JvmInline
value class AccountId(val value: String) {
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

data object HomeModel

data class DiaryModel(
	val dayCount: Int,
) {
	init {
		require(dayCount >= 0) { "Diary day count must not be negative" }
	}
}

data class ScheduleModel(
	val dayCount: Int,
) {
	init {
		require(dayCount >= 0) { "Schedule day count must not be negative" }
	}
}

data class GradesModel(
	val subjectCount: Int,
) {
	init {
		require(subjectCount >= 0) { "Grade subject count must not be negative" }
	}
}

data class ProfileModel(
	val accountId: AccountId,
	val schoolLabel: String?,
	val groupLabel: String?,
) {
	init {
		require(schoolLabel?.isNotBlank() != false) { "School label must not be blank" }
		require(groupLabel?.isNotBlank() != false) { "Group label must not be blank" }
	}
}

data object NotificationsModel
