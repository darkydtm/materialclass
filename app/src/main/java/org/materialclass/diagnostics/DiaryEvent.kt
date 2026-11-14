package org.materialclass.diagnostics

import org.materialclass.api.AppError

enum class DiaryStage { LOAD, REPOSITORY, SUBJECTS, JOURNAL, SERIALIZE, SNAPSHOT, CACHE }
enum class DiaryOutcome { START, SUCCESS, EMPTY, FAILURE, PARTIAL, SKIPPED }

internal fun diaryMessage(stage: DiaryStage, outcome: DiaryOutcome, status: Int?, error: AppError?, exception: Throwable?): String {
	val errorName = when (error) {
		null -> "none"
		is AppError.HttpStatus -> "HttpStatus"
		else -> error.javaClass.simpleName
	}
	val exceptionName = when (exception) {
		null -> "none"
		is java.io.NotSerializableException -> "NotSerializableException"
		is java.net.SocketTimeoutException -> "SocketTimeoutException"
		is java.net.UnknownHostException -> "UnknownHostException"
		is javax.net.ssl.SSLException -> "SSLException"
		is java.io.IOException -> "IOException"
		is IllegalArgumentException -> "IllegalArgumentException"
		is SecurityException -> "SecurityException"
		else -> "OtherException"
	}
	return "stage=${stage.name} outcome=${outcome.name} status=${status?.takeIf { it in 100..599 } ?: 0} error=$errorName exception=$exceptionName"
}
