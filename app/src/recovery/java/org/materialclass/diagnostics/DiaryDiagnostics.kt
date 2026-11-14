package org.materialclass.diagnostics

import android.util.Log
import org.materialclass.api.AppError

object DiaryDiagnostics {
	internal var sink: (String) -> Unit = { message -> runCatching { Log.d("DiaryDiagnostic", message) } }
	fun upgradeRequired(contentType: String, body: String) {
		val text = body.lowercase(java.util.Locale.ROOT)
		val type = contentType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
		sink("upgrade426 empty=${body.isEmpty()} json=${type == "application/json"} html=${type == "text/html"} cloudflare=${text.contains("cloudflare")} tls=${text.contains("tls")} version=${text.contains("version")} upgrade=${text.contains("upgrade")} message=${text.contains("\"message\"")} error=${text.contains("\"error\"")}")
	}
	fun record(stage: DiaryStage, outcome: DiaryOutcome, status: Int? = null, error: AppError? = null, exception: Throwable? = null) {
		sink(diaryMessage(stage, outcome, status, error, exception))
	}
}
