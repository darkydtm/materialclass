package org.materialclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.materialclass.BuildConfig
import org.materialclass.notifications.NotificationCategory
import org.materialclass.notifications.NotificationRunResult

internal fun readApplicationLogs(
	pid: Int,
	execute: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
): String {
	require(pid > 0) { "Application PID must be positive" }
	val process = execute(arrayOf("logcat", "-d", "-v", "threadtime"))
	val output = process.inputStream.bufferedReader().useLines { lines ->
		lines.filter { line ->
			val fields = line.trimStart().split(Regex("\\s+"), limit = 6)
			fields.size == 6 && fields[2].toIntOrNull() == pid
		}.joinToString("\n")
	}
	check(process.waitFor() == 0) { "logcat failed" }
	return output
}

@Composable
internal fun DebugScreen(padding: PaddingValues, text: (String) -> String, simulate: (NotificationCategory) -> NotificationRunResult) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var status by remember { mutableStateOf("") }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Text("Date: ${BuildConfig.BUILD_DATE}\nHash: ${BuildConfig.BUILD_REVISION}\nBranch: ${BuildConfig.BUILD_BRANCH}", style = MaterialTheme.typography.bodyMedium)
		Button(onClick = clickFeedback { scope.launch { status = withContext(Dispatchers.IO) { simulate(NotificationCategory.NEW_GRADE).status.name } } }, modifier = Modifier.fillMaxWidth()) { Text(text("debug_new_grade")) }
		Button(onClick = clickFeedback { scope.launch { status = withContext(Dispatchers.IO) { simulate(NotificationCategory.HOMEWORK).status.name } } }, modifier = Modifier.fillMaxWidth()) { Text(text("debug_homework")) }
		Button(onClick = clickFeedback {
			scope.launch {
				val logs = withContext(Dispatchers.IO) {
					runCatching { readApplicationLogs(Process.myPid()) }.getOrNull()
				}
				if (logs == null) status = text("debug_logs_failed") else {
					(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MaterialClass logcat", logs))
					status = text("debug_logs_copied")
				}
			}
		}, modifier = Modifier.fillMaxWidth()) { Text(text("debug_export_logs")) }
		Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}