package org.materialclass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val loginRoutes = setOf(
	AppRoute.Login,
	AppRoute.LoginLoading,
	AppRoute.LoginInvalid,
	AppRoute.LoginTwoFactor,
	AppRoute.LoginConnectionError,
)

@Composable
internal fun LoginFlow(text: (String) -> String, viewModel: SchoolViewModel) {
	LoginScreen(text, viewModel)
}

@Composable
private fun LoginScreen(text: (String) -> String, viewModel: SchoolViewModel) {
	var login by rememberSaveable { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	var loginError by rememberSaveable { mutableStateOf(false) }
	var passwordError by rememberSaveable { mutableStateOf(false) }
	var loginFocused by remember { mutableStateOf(false) }
	var passwordFocused by remember { mutableStateOf(false) }
	val focusManager = LocalFocusManager.current
	val loginFocusRequester = remember { FocusRequester() }
	val loading = viewModel.loginState == LoginUiState.Loading
	val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
	val primary = if (dark) Color(0xFFC9BFFF) else Color(0xFF5B3FD1)
	val onPrimary = if (dark) Color(0xFF2A0F8A) else Color.White
	val fieldColor = if (dark) Color(0xFF2B2930) else Color(0xFFF3EDF7)
	val tonalColor = if (dark) Color(0xFF4A4458) else Color(0xFFE8DEF8)
	val onTonal = if (dark) Color(0xFFE8DEF8) else Color(0xFF1D192B)
	val errorColor = MaterialTheme.colorScheme.error
	val bannerMessage = when (viewModel.loginState) {
		LoginUiState.InvalidCredentials -> text("login_invalid")
		LoginUiState.AccessDenied -> text("login_access_denied")
		LoginUiState.ConnectionError -> text("login_connection_error_body")
		LoginUiState.Unsupported -> text("login_unsupported")
		else -> null
	}
	val submit: () -> Unit = {
		loginError = login.isBlank()
		passwordError = password.isBlank()
		when {
			loginError -> loginFocusRequester.requestFocus()
			passwordError -> focusManager.moveFocus(FocusDirection.Down)
			else -> {
				focusManager.clearFocus()
				viewModel.login(login.trim(), password)
			}
		}
	}
	BackHandler(enabled = loading) { viewModel.cancelLogin() }

	Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
		Column(
			modifier = Modifier.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 24.dp, vertical = 24.dp)
				.testTag("login-screen"),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Column(
				modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = text("login_welcome"),
					fontSize = 40.sp,
					lineHeight = 44.sp,
					fontWeight = FontWeight.ExtraBold,
					letterSpacing = (-0.5).sp,
				)
				Text(
					text = text("login_prompt"),
					modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (bannerMessage != null) {
					Surface(
						modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
						shape = RoundedCornerShape(20.dp),
						color = MaterialTheme.colorScheme.errorContainer,
					) {
						Text(
							text = bannerMessage,
							modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onErrorContainer,
						)
					}
				}
				TextField(
					value = login,
					onValueChange = {
						login = it
						loginError = false
						viewModel.resetLogin()
					},
					modifier = Modifier.fillMaxWidth().height(60.dp)
					.focusRequester(loginFocusRequester)
					.onFocusChanged { loginFocused = it.isFocused }
					.border(
						width = if (loginFocused || loginError) 2.dp else 0.dp,
						color = if (loginError) errorColor else primary,
						shape = RoundedCornerShape(30.dp),
					)
					.testTag("login-input"),
					enabled = !loading,
					singleLine = true,
					shape = RoundedCornerShape(30.dp),
					label = { Text(text("username")) },
					isError = loginError,
					colors = TextFieldDefaults.colors(
						focusedContainerColor = fieldColor,
						unfocusedContainerColor = fieldColor,
						disabledContainerColor = fieldColor,
						focusedIndicatorColor = Color.Transparent,
						unfocusedIndicatorColor = Color.Transparent,
						disabledIndicatorColor = Color.Transparent,
						errorIndicatorColor = errorColor,
						cursorColor = primary,
					),
					keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
					keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
				)
				Text(
					text = if (loginError) text("login_required") else " ",
					modifier = Modifier.fillMaxWidth().padding(start = 22.dp).height(20.dp),
					style = MaterialTheme.typography.bodySmall,
					color = errorColor,
				)
				TextField(
					value = password,
					onValueChange = {
						password = it
						passwordError = false
						viewModel.resetLogin()
					},
					modifier = Modifier.fillMaxWidth().height(60.dp)
					.onFocusChanged { passwordFocused = it.isFocused }
					.border(
						width = if (passwordFocused || passwordError) 2.dp else 0.dp,
						color = if (passwordError) errorColor else primary,
						shape = RoundedCornerShape(30.dp),
					)
					.testTag("password-input"),
					enabled = !loading,
					singleLine = true,
					shape = RoundedCornerShape(30.dp),
					label = { Text(text("password")) },
					isError = passwordError,
					visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
					trailingIcon = {
						IconButton(
							enabled = !loading,
							onClick = clickFeedback { passwordVisible = !passwordVisible },
						) {
							Icon(
								if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
								contentDescription = text(if (passwordVisible) "login_hide_password" else "login_show_password"),
							)
						}
					},
					colors = TextFieldDefaults.colors(
						focusedContainerColor = fieldColor,
						unfocusedContainerColor = fieldColor,
						disabledContainerColor = fieldColor,
						focusedIndicatorColor = Color.Transparent,
						unfocusedIndicatorColor = Color.Transparent,
						disabledIndicatorColor = Color.Transparent,
						errorIndicatorColor = errorColor,
						cursorColor = primary,
					),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { submit() }),
				)
				Text(
					text = if (passwordError) text("login_required_password") else " ",
					modifier = Modifier.fillMaxWidth().padding(start = 22.dp).height(20.dp),
					style = MaterialTheme.typography.bodySmall,
					color = errorColor,
				)
				Button(
					onClick = clickFeedback(submit),
					enabled = !loading,
					modifier = Modifier.fillMaxWidth().height(64.dp).testTag("login-button"),
					shape = RoundedCornerShape(32.dp),
					colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = onPrimary),
				) {
					if (loading) {
						CircularProgressIndicator(modifier = Modifier.size(22.dp), color = onPrimary, strokeWidth = 2.dp)
						Spacer(Modifier.size(12.dp))
						Text(text("login_loading_short"))
					} else Text(text("sign_in"))
				}
				if (loading) {
					Button(
						onClick = clickFeedback { viewModel.cancelLogin() },
						modifier = Modifier.fillMaxWidth().height(64.dp).testTag("cancel-login-button"),
						shape = RoundedCornerShape(32.dp),
						colors = ButtonDefaults.buttonColors(containerColor = tonalColor, contentColor = onTonal),
					) { Text(text("login_cancel")) }
				}
			}
		}
	}
}
