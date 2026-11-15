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

@Composable
internal fun DebugScreen(padding: PaddingValues, text: (String) -> String, simulate: (NotificationCategory) -> NotificationRunResult) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var status by remember { mutableStateOf("") }
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Text("${BuildConfig.BUILD_DATE}\n${BuildConfig.BUILD_BRANCH}\n${BuildConfig.BUILD_REVISION}", style = MaterialTheme.typography.bodyMedium)
		Button(onClick = { scope.launch { status = withContext(Dispatchers.IO) { simulate(NotificationCategory.NEW_GRADE).status.name } } }, modifier = Modifier.fillMaxWidth()) { Text(text("debug_new_grade")) }
		Button(onClick = { scope.launch { status = withContext(Dispatchers.IO) { simulate(NotificationCategory.HOMEWORK).status.name } } }, modifier = Modifier.fillMaxWidth()) { Text(text("debug_homework")) }
		Button(onClick = {
			scope.launch {
				val logs = withContext(Dispatchers.IO) { runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=${Process.myPid()}" )).inputStream.bufferedReader().use { it.readText() } }.getOrNull() }
				if (logs == null) status = text("debug_logs_failed") else {
					(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MaterialClass logcat", logs))
					status = text("debug_logs_copied")
				}
			}
		}, modifier = Modifier.fillMaxWidth()) { Text(text("debug_export_logs")) }
		Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}