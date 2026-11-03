package org.materialclass

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.SessionCoordinator
import org.materialclass.data.DiaryState
import java.util.Locale

internal data class ExampleData(val diary: List<String>, val schedule: List<String>)

internal fun blackColors() = darkColorScheme(
	background = Color.Black,
	surface = Color.Black,
	surfaceContainerLowest = Color.Black,
	surfaceContainerLow = Color(0xFF101010),
	surfaceContainer = Color(0xFF171717),
	surfaceContainerHigh = Color(0xFF202020),
	surfaceContainerHighest = Color(0xFF292929)
)

@Composable
internal fun MaterialClassApp(example: ExampleData? = null, nativeEnabled: Boolean = BuildConfig.NATIVE_UI_ENABLED, controllerFactory: (android.content.Context) -> NativeUiController = ::createNativeController) {
	val base = LocalContext.current
	val controller = remember(nativeEnabled) { if (nativeEnabled) controllerFactory(base) else null }
	DisposableEffect(controller) {
		controller?.start()
		onDispose { controller?.close() }
	}
	val nativeState = controller?.state
	val preferences = remember { base.getSharedPreferences("appearance", 0) }
	var language by remember { mutableStateOf(preferences.getString("language", "system") ?: "system") }
	var theme by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
	var warning by remember { mutableStateOf(!preferences.getBoolean("warning_seen", false)) }
	var screen by remember { mutableStateOf("login") }
	val systemConfiguration = LocalConfiguration.current
	val localized = remember(language, systemConfiguration) {
		if (language == "system") base else {
			val configuration = Configuration(systemConfiguration)
			configuration.setLocale(Locale.forLanguageTag(language))
			base.createConfigurationContext(configuration)
		}
	}
	CompositionLocalProvider(LocalContext provides localized) {
		val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
		val colors = when {
			theme == "black" -> blackColors()
			Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(base)
			Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(base)
			dark -> darkColorScheme()
			else -> lightColorScheme()
		}
		MaterialExpressiveTheme(colorScheme = colors) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
					if (controller == null) Text(stringResource(R.string.backend_unavailable), color = MaterialTheme.colorScheme.error)
					nativeState?.error?.let { Text(stringResource(R.string.operation_error, it.name), color = MaterialTheme.colorScheme.error) }
					if (warning) {
						Text(stringResource(R.string.warning_title), style = MaterialTheme.typography.titleMedium)
						Text(stringResource(R.string.warning_text))
						TextButton(onClick = { warning = false; preferences.edit().putBoolean("warning_seen", true).apply() }) { Text(stringResource(R.string.ok)) }
					}
					Row(modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf("login" to R.string.login, "diary" to R.string.diary, "schedule" to R.string.schedule, "settings" to R.string.settings).forEach { (value, label) ->
							Box(Modifier.selectable(selected = screen == value, role = Role.Tab, onClick = { screen = value }).padding(12.dp)) { Text(stringResource(label)) }
						}
					}
					when (screen) {
						"login" -> LoginScreen(controller, nativeState)
						"diary", "schedule" -> {
							Text(stringResource(if (screen == "diary") R.string.diary else R.string.schedule), style = MaterialTheme.typography.headlineSmall)
							if (nativeState != null) {
								if (!nativeState.signedIn) Text(stringResource(R.string.data_unavailable))
								else if (screen == "diary") {
									when (val diary = nativeState.diary) {
										is DiaryState.Ready -> {
											diary.staleError?.let { Text(stringResource(R.string.stale_data, it.name)) }
											diary.snapshot.diary.days.forEach { day -> Text(day.date); day.subjects.forEach { subject -> Text(subject.label); subject.homeworkBody?.let { Text(it) } } }
										}
										DiaryState.Loading -> Text(stringResource(R.string.loading))
										else -> Text(stringResource(R.string.data_unavailable))
									}
								} else nativeState.schedule?.days?.forEach { day -> Text(day.dateFormat); day.lessons.forEach { Text(it.themeLabel) } } ?: Text(stringResource(R.string.data_unavailable))
								if (nativeState.signedIn) Button(onClick = { controller.refresh() }) { Text(stringResource(R.string.refresh)) }
							} else if (example == null) Text(stringResource(R.string.data_unavailable)) else {
								Text(stringResource(R.string.example_only))
								(screen.let { if (it == "diary") example.diary else example.schedule }).forEach { Text(it) }
							}
						}
						"settings" -> {
							Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
							Column(Modifier.selectableGroup()) {
								listOf("system" to R.string.system, "ru" to R.string.russian, "kk" to R.string.kazakh, "en" to R.string.english).forEach { (value, label) ->
									TextButton(onClick = { language = value; preferences.edit().putString("language", value).apply() }, modifier = Modifier.semantics { selected = language == value; role = Role.RadioButton }) { Text((if (language == value) "✓ " else "") + stringResource(label)) }
								}
							}
							Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleLarge)
							Column(Modifier.selectableGroup()) {
								listOf("system" to R.string.system, "light" to R.string.light, "dark" to R.string.dark, "black" to R.string.black).forEach { (value, label) ->
									TextButton(onClick = { theme = value; preferences.edit().putString("theme", value).apply() }, modifier = Modifier.semantics { selected = theme == value; role = Role.RadioButton }) { Text((if (theme == value) "✓ " else "") + stringResource(label)) }
								}
							}
							var clearStatus by remember { mutableStateOf<Int?>(null) }
							Button(onClick = {
								clearStatus = try { EncryptedCache(base, SessionCoordinator(base)).clearAll(); R.string.cache_cleared } catch (_: Exception) { R.string.cache_error }
							}) { Text(stringResource(R.string.clear_cache)) }
							clearStatus?.let { Text(stringResource(it)) }
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LoginScreen(controller: NativeUiController?, state: NativeUiState?) {
	var login by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	Text(stringResource(R.string.login), style = MaterialTheme.typography.headlineSmall)
	if (state?.logoutPending == true) {
		Text(stringResource(if (state.busy) R.string.loading else R.string.logout_cleanup_failed))
		if (!state.busy) Button(onClick = { controller?.logout() }) { Text(stringResource(R.string.retry_logout)) }
		return
	}
	if (state?.signedIn == true) {
		Button(onClick = { controller?.logout() }) { Text(stringResource(R.string.logout)) }
		return
	}
	OutlinedTextField(value = login, onValueChange = { login = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true)
	OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
	Button(onClick = { val secret = password.toCharArray(); password = ""; controller?.login(login, secret) ?: secret.fill('\u0000') }, enabled = controller != null && state?.busy == false) { Text(stringResource(R.string.sign_in)) }
}
