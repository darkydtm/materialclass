package org.materialclass.diagnostics

import org.materialclass.api.AppError

object DiaryDiagnostics {
	fun record(stage: DiaryStage, outcome: DiaryOutcome, status: Int? = null, error: AppError? = null, exception: Throwable? = null) = Unit
}
