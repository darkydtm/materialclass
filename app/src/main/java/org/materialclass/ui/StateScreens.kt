package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StateScreen(padding: PaddingValues, text: (String) -> String, bar: String, title: String, body: String, retry: () -> Unit) {
	Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(text(title), style = MaterialTheme.typography.headlineLarge)
					Text(text(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = if (title == text("empty_day")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
					Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
						if (title == text("empty_day")) Text("-", style = MaterialTheme.typography.displaySmall)
						Text(if (title == text("empty_day")) text("empty_hint") else text("offline_hint"), style = MaterialTheme.typography.titleLarge)
						if (title != text("empty_day")) Text(text("offline_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
				}
				if (title == text("error_load")) Button(onClick = retry, shape = RowShape, modifier = Modifier.fillMaxWidth()) { Text(text("retry")) }
			}
	}
}

@Composable
internal fun StatePanel(text: (String) -> String, state: UiContent, retry: (() -> Unit)? = null) {
	Card(shape = RowShape, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			when (state) {
				UiContent.Loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
				UiContent.Ready -> Text(text("ready"))
				UiContent.Empty -> Text(text("empty_day"))
				UiContent.Offline -> Text(text("offline_title"))
				UiContent.Stale -> Text(text("offline_title"))
				UiContent.Error -> Text(text("error_load"), color = MaterialTheme.colorScheme.error)
				UiContent.Unsupported -> Text(text("unsupported"), color = MaterialTheme.colorScheme.error)
			}
			if (retry != null && state != UiContent.Ready && state != UiContent.Unsupported) TextButton(onClick = retry) { Text(text("retry")) }
		}
	}
}
