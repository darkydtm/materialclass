package org.materialclass.diagnostics

import android.util.Log
import org.materialclass.api.AppError

object DiaryDiagnostics {
	internal var sink: (String) -> Unit = { message -> runCatching { Log.d("DiaryDiagnostic", message) } }
	fun record(stage: DiaryStage, outcome: DiaryOutcome, status: Int? = null, error: AppError? = null, exception: Throwable? = null) {
		sink(diaryMessage(stage, outcome, status, error, exception))
	}
}
