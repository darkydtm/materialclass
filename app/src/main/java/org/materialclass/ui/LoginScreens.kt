package org.materialclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal val loginRoutes = setOf(
	AppRoute.Login,
	AppRoute.LoginLoading,
	AppRoute.LoginInvalid,
	AppRoute.LoginTwoFactor,
	AppRoute.LoginConnectionError,
)

internal fun loginRoute(state: LoginUiState): AppRoute = when (state) {
	LoginUiState.Ready, LoginUiState.Confirmed -> AppRoute.Login
	LoginUiState.Loading -> AppRoute.LoginLoading
	LoginUiState.InvalidCredentials -> AppRoute.LoginInvalid
	LoginUiState.ConnectionError, LoginUiState.Unsupported -> AppRoute.LoginConnectionError
}

@Composable
internal fun LoginFlow(text: (String) -> String, viewModel: SchoolViewModel, route: AppRoute, reset: () -> Unit) {
	when (route) {
		AppRoute.Login -> LoginScreen(text, viewModel)
		AppRoute.LoginLoading -> LoginStateScreen(text("login_checking"), text("login_loading"), text, reset)
		AppRoute.LoginInvalid -> LoginStateScreen(text("login_failed"), text("login_invalid"), text, reset)
		AppRoute.LoginTwoFactor -> LoginStateScreen(text("login_2fa"), text("login_2fa_body"), text, reset)
		AppRoute.LoginConnectionError -> LoginStateScreen(text("login_connection_error"), text("login_connection_error_body"), text, reset)
		else -> Unit
	}
}

@Composable
private fun LoginScreen(text: (String) -> String, viewModel: SchoolViewModel) {
	var login by rememberSaveable { mutableStateOf("") }
	var password by rememberSaveable { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp).testTag("login-screen"),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Column(modifier = Modifier.width(340.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					OutlinedTextField(
						value = login,
						onValueChange = { login = it },
						label = { Text(text("username")) },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true,
					)
					OutlinedTextField(
						value = password,
						onValueChange = { password = it },
						label = { Text(text("password")) },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true,
						visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
						trailingIcon = {
							IconButton(onClick = { passwordVisible = !passwordVisible }) {
								Icon(
									if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
									contentDescription = text(if (passwordVisible) "login_hide_password" else "login_show_password"),
								)
							}
						},
					)
					when (viewModel.loginState) {
						LoginUiState.InvalidCredentials -> Text(text("login_invalid"), color = MaterialTheme.colorScheme.error)
						LoginUiState.ConnectionError -> Text(text("login_connection_error_body"), color = MaterialTheme.colorScheme.error)
						LoginUiState.Unsupported -> Text(text("unsupported"), color = MaterialTheme.colorScheme.error)
						LoginUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
						else -> Unit
					}
					Button(
						onClick = { viewModel.login(login, password) },
						modifier = Modifier.fillMaxWidth().testTag("login-button"),
						shape = RowShape,
					) { Text(text("sign_in")) }
				}
			}
		}
		Text(text("login_connection_error_body").substringBefore('.'), modifier = Modifier.width(340.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}
@Composable
private fun LoginStateScreen(title: String, body: String, text: (String) -> String, reset: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().padding(20.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		val isLoading = title == text("login_checking")
		val isError = title == text("login_failed") || title == text("login_connection_error")
		Card(
			shape = CardShape,
			colors = CardDefaults.cardColors(
				containerColor = when {
					isError -> MaterialTheme.colorScheme.errorContainer
					isLoading -> MaterialTheme.colorScheme.tertiaryContainer
					else -> MaterialTheme.colorScheme.secondaryContainer
				},
			),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				if (isLoading) CircularProgressIndicator(modifier = Modifier.size(32.dp))
				else Icon(if (isError) Icons.Default.Close else Icons.Default.Check, contentDescription = null)
				Text(title, style = MaterialTheme.typography.headlineMedium)
				Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
				if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				else Button(onClick = reset, shape = RowShape, modifier = Modifier.fillMaxWidth()) { Text(text(if (isError && title == text("login_connection_error")) "login_try_again" else "login_again")) }
			}
		}
	}
}
