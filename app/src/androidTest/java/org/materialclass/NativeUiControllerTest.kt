package org.materialclass

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.data.DiarySnapshot
import org.materialclass.data.DiaryState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeUiControllerTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
		if (node.text?.toString() == text) return node
		return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, text) } }
	}
	private fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		if (node.isScrollable) return node
		return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::scrollable) }
	}
	private fun waitFor(text: String): AccessibilityNodeInfo {
		val deadline = SystemClock.uptimeMillis() + 10000
		var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
		while (SystemClock.uptimeMillis() < deadline) {
			val root = instrumentation.uiAutomation.rootInActiveWindow
			root?.let { find(it, text) }?.let { return it }
			val scroller = root?.let(::scrollable)
			if (scroller != null && !scroller.performAction(direction)) {
				direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
			}
			SystemClock.sleep(100)
		}
		throw AssertionError("Missing node: $text")
	}
	private fun click(text: String) {
		val deadline = SystemClock.uptimeMillis() + 10000
		while (SystemClock.uptimeMillis() < deadline) {
			var node = waitFor(text)
			while (!node.isClickable && node.parent != null) node = node.parent
			if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
			SystemClock.sleep(100)
		}
		throw AssertionError("Unable to click: $text")
	}
	private fun edit(node: AccessibilityNodeInfo, value: String) {
		val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
	}
	private fun editables(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = (if (node.isEditable) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::editables) ?: emptyList() }

	@Test fun homeworkNavigationShowsDiaryHomework() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(Diary("01.01.2026", listOf(DiaryDay("Thursday", "01.01.2026", listOf(DiarySubject("Mathematics", "lesson", "id", "text", "Solve exercises", null, false, false))))), 1))))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { state = NativeUiState() }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click("Homework")
			waitFor("Mathematics")
			waitFor("Solve exercises")
			waitFor("Record date: 01.01.2026")
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun homeworkTracksDiaryStatesAndAccountChanges() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		fun subject(label: String, body: String?, books: Boolean = false, files: Boolean = false) = DiarySubject(label, "lesson", "id", "text", body, null, books, files)
		val diary = Diary("01.01.2026", listOf(
			DiaryDay("Thursday", "01.01.2026", listOf(subject("No body", null), subject("Empty body", ""), subject("Math", "First assignment", files = true))),
			DiaryDay("Friday", "02.01.2026", listOf(subject("History", "Second assignment", books = true)))
		))
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true, diary = DiaryState.Loading))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { state = NativeUiState() }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click("Homework")
			waitFor(context.getString(R.string.loading))
			instrumentation.runOnMainSync { fake.state = fake.state.copy(diary = DiaryState.Ready(DiarySnapshot(diary, 1), AppError.OFFLINE)) }
			waitFor("First assignment")
			waitFor("Second assignment")
			waitFor("Record date: 01.01.2026")
			waitFor("Record date: 02.01.2026")
			waitFor(context.getString(R.string.homework_files))
			waitFor(context.getString(R.string.homework_books))
			waitFor(context.getString(R.string.stale_data, context.getString(R.string.error_offline)))
			val root = instrumentation.uiAutomation.rootInActiveWindow
			assertNull(find(root, "No body"))
			assertNull(find(root, "Empty body"))
			assertTrue(waitFor("First assignment").boundsInScreenTop() < waitFor("Second assignment").boundsInScreenTop())
			instrumentation.runOnMainSync { fake.state = fake.state.copy(diary = DiaryState.Ready(DiarySnapshot(Diary("", listOf(DiaryDay("", "", listOf(subject("No body", null), subject("Empty body", ""))))), 1))) }
			waitFor(context.getString(R.string.homework_empty))
			instrumentation.runOnMainSync { fake.state = fake.state.copy(diary = DiaryState.Error(AppError.OFFLINE)) }
			waitFor(context.getString(R.string.error_offline))
			waitFor(context.getString(R.string.data_unavailable))
			instrumentation.runOnMainSync { fake.state = NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(diary, 1))) }
			waitFor("First assignment")
			instrumentation.runOnMainSync { fake.state = NativeUiState() }
			waitFor(context.getString(R.string.data_unavailable))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "First assignment"))
			instrumentation.runOnMainSync { fake.state = NativeUiState(signedIn = true, diary = DiaryState.Loading) }
			waitFor(context.getString(R.string.loading))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "First assignment"))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	private fun AccessibilityNodeInfo.boundsInScreenTop(): Int = android.graphics.Rect().also { getBoundsInScreen(it) }.top

	@Test fun homeworkFilterOptionsExposeLocalizedRadioSelection() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val labels = listOf(
			listOf("en", "Homework", "Show filters", "All subjects", "Subject: Math", "All dates", "Date: day one"),
			listOf("ru", "Домашнее задание", "Показать фильтры", "Все предметы", "Предмет: Math", "Все даты", "Дата: day one"),
			listOf("kk", "Үй тапсырмасы", "Сүзгілерді көрсету", "Барлық пәндер", "Пән: Math", "Барлық күндер", "Күн: day one")
		)
		for (row in labels) {
			val (language, navigation, show, allSubjects, subject) = row
			val allDates = row[5]
			val date = row[6]
			context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", language).commit()
			val fake = object : NativeUiController {
				override val state = NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(Diary("week", listOf(DiaryDay("one", "day one", listOf(DiarySubject("Math", "lesson", "id", "text", "Task", null, false, false))))), 1)))
				override fun start() { }
				override fun login(username: String, password: CharArray) { password.fill('\u0000') }
				override fun refresh() { }
				override fun logout() { }
				override fun close() { }
			}
			val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
			try {
				instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
				val logout = when (language) { "ru" -> "Выйти"; "kk" -> "Шығу"; else -> "Sign out" }
				waitFor(logout)
				click(navigation)
				click(show)
				fun checkRadio(label: String, checked: Boolean) {
					var node = waitFor(label)
					while (!node.isClickable && node.parent != null) node = node.parent
					assertEquals("android.widget.RadioButton", node.className.toString())
					assertTrue(node.isCheckable)
					assertEquals(label, checked, node.isChecked)
				}
				checkRadio(allSubjects, true)
				checkRadio(subject, false)
				click(subject)
				instrumentation.waitForIdleSync()
				checkRadio(subject, true)
				checkRadio(allSubjects, false)
				checkRadio(allDates, true)
				checkRadio(date, false)
				click(date)
				instrumentation.waitForIdleSync()
				checkRadio(date, true)
				checkRadio(allDates, false)
			} finally {
				activity.finish()
				context.getSharedPreferences("appearance", 0).edit().clear().commit()
			}
		}
	}

	@Test fun homeworkFiltersOnlyTheCurrentSnapshotAndClearsOnSignOut() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		fun item(label: String, body: String) = DiarySubject(label, "lesson", "id", "text", body, null, false, false)
		val diary = Diary("week", listOf(DiaryDay("one", "day one", listOf(item("Math", "Math task"), item("Math", "Second math task"))), DiaryDay("two", "day two", listOf(item("Art", "Art task")))))
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(diary, 1))))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { state = NativeUiState() }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click("Homework")
			click("Show filters")
			click("Subject: Math")
			click("Hide filters")
			waitFor("Math task")
			waitFor("Second math task")
			val filteredRoot = instrumentation.uiAutomation.rootInActiveWindow
			assertTrue(find(filteredRoot, "Math task")!!.boundsInScreenTop() < find(filteredRoot, "Second math task")!!.boundsInScreenTop())
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Art task"))
			click("Show filters")
			click("All subjects")
			click("Hide filters")
			waitFor("Art task")
			click("Show filters")
			click("Date: day two")
			click("Hide filters")
			waitFor("Art task")
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Math task"))
			click("Show filters")
			click("All dates")
			click("Hide filters")
			waitFor("Math task")
			click("Show filters")
			click("Subject: Math")
			click("Hide filters")
			instrumentation.runOnMainSync { fake.state = NativeUiState() }
			waitFor(context.getString(R.string.data_unavailable))
			instrumentation.runOnMainSync { fake.state = NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(diary, 2))) }
			waitFor("Art task")
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun diaryShowsAttachmentIndicatorsWithoutHomeworkBody() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(Diary("01.01.2026", listOf(DiaryDay("Thursday", "01.01.2026", listOf(DiarySubject("Math <&>", "lesson", "id", "none", null, null, true, true))))), 1))))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { state = NativeUiState() }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click("Diary")
			waitFor("Math <&>")
			waitFor(context.getString(R.string.homework_files))
			waitFor(context.getString(R.string.homework_books))
			instrumentation.runOnMainSync { fake.state = NativeUiState() }
			waitFor(context.getString(R.string.data_unavailable))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Math <&>"))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun emptyDiaryIsDistinctFromUnavailable() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).commit()
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click(context.getString(R.string.diary))
			waitFor(context.getString(R.string.data_unavailable))
			instrumentation.runOnMainSync {
				fake.state = fake.state.copy(diary = DiaryState.Ready(DiarySnapshot(Diary("01.01.2026", emptyList()), 1)))
			}
			waitFor(context.getString(R.string.diary_empty))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.data_unavailable)))
			instrumentation.runOnMainSync { fake.state = fake.state.copy(diary = DiaryState.Error(AppError.OFFLINE)) }
			waitFor(context.getString(R.string.data_unavailable))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.diary_empty)))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun emptyScheduleIsDistinctFromUnavailable() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).commit()
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(signedIn = true))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor(context.getString(R.string.logout))
			click(context.getString(R.string.schedule))
			waitFor(context.getString(R.string.data_unavailable))
			instrumentation.runOnMainSync {
				fake.state = fake.state.copy(schedule = Schedule((1..7).map { ScheduleDay(it.toLong(), "0$it.01.2026", false, false, emptyList()) }))
			}
			waitFor(context.getString(R.string.schedule_empty))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.data_unavailable)))
			instrumentation.runOnMainSync { fake.state = fake.state.copy(schedule = null, error = AppError.INCOMPATIBLE_RESPONSE) }
			waitFor("The response could not be understood. Try again later.")
			waitFor(context.getString(R.string.data_unavailable))
			assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.schedule_empty)))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun expiryIsAccessibleAndDistinctFromTransientLoginError() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState(error = AppError.SESSION_EXPIRED))
			override fun start() { }
			override fun login(username: String, password: CharArray) { password.fill('\u0000') }
			override fun refresh() { }
			override fun logout() { }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			waitFor("Your session has expired. Sign in again.")
			instrumentation.runOnMainSync { fake.state = NativeUiState(error = AppError.OFFLINE) }
			waitFor("No network connection. Check your connection and try again.")
			assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Your session has expired. Sign in again.") })
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	@Test fun localizedErrorsAreVisibleForLoginAndCachedDiary() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val messages = mapOf(
			"en" to listOf("No network connection. Check your connection and try again.", "The request timed out. Try again.", "The service is unavailable. Try again later.", "The response could not be understood. Try again later.", "Something went wrong. Try again later.", "Your session has expired. Sign in again.", "Saved data could not be read."),
			"ru" to listOf("Нет подключения к сети. Проверьте соединение и повторите попытку.", "Время ожидания запроса истекло. Повторите попытку.", "Сервис недоступен. Повторите попытку позже.", "Не удалось обработать ответ. Повторите попытку позже.", "Что-то пошло не так. Повторите попытку позже.", "Срок действия сеанса истёк. Войдите снова.", "Не удалось прочитать сохранённые данные."),
			"kk" to listOf("Желіге қосылым жоқ. Қосылымды тексеріп, қайталап көріңіз.", "Сұраудың күту уақыты аяқталды. Қайталап көріңіз.", "Қызмет қолжетімсіз. Кейінірек қайталап көріңіз.", "Жауапты өңдеу мүмкін болмады. Кейінірек қайталап көріңіз.", "Бір қате орын алды. Кейінірек қайталап көріңіз.", "Сеанс мерзімі аяқталды. Қайта кіріңіз.", "Сақталған деректерді оқу мүмкін болмады.")
		)
		val errors = listOf(AppError.OFFLINE, AppError.TIMEOUT, AppError.UNAVAILABLE, AppError.INCOMPATIBLE_RESPONSE, AppError.INVALID_CREDENTIALS, AppError.SESSION_EXPIRED, AppError.CACHE_UNREADABLE)
		for ((language, expected) in messages) {
			context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", language).commit()
			val fake = object : NativeUiController {
				override var state by mutableStateOf(NativeUiState())
				override fun start() { }
				override fun login(username: String, password: CharArray) { password.fill('\u0000') }
				override fun refresh() { }
				override fun logout() { }
				override fun close() { }
			}
			val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
			try {
				instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
				for ((index, error) in errors.withIndex()) {
					instrumentation.runOnMainSync { fake.state = NativeUiState(error = error) }
					waitFor(expected[index])
					assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, error.name) })
				}
				for (error in listOf(AppError.FORBIDDEN, AppError.SERVER)) {
					instrumentation.runOnMainSync { fake.state = NativeUiState(error = error) }
					waitFor(expected[4])
				}
				instrumentation.runOnMainSync { fake.state = NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(Diary("01.01.2026", emptyList()), 1), AppError.OFFLINE)) }
				val diaryLabel = when (language) { "ru" -> "Дневник"; "kk" -> "Күнделік"; else -> "Diary" }
				click(diaryLabel)
				val stale = when (language) { "ru" -> "Сохраненные данные - ошибка обновления: "; "kk" -> "Сақталған деректер - жаңарту қатесі: "; else -> "Cached data - refresh failed: " }
				waitFor(stale + expected[0])
			} finally {
				activity.finish()
				context.getSharedPreferences("appearance", 0).edit().clear().commit()
			}
		}
	}

	@Test fun injectedControllerDrivesVisibleUi() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).commit()
		val started = CountDownLatch(1)
		val fake = object : NativeUiController {
			override var state by mutableStateOf(NativeUiState())
			var secretCleared = false
			override fun start() { started.countDown() }
			override fun login(username: String, password: CharArray) {
				password.fill('\u0000'); secretCleared = password.all { it == '\u0000' }
				val diary = Diary("01.01.2026", listOf(DiaryDay("Thursday", "01.01.2026", listOf(DiarySubject("UI lesson", "lesson", "id", "text", null, null, false, false)))))
				state = NativeUiState(signedIn = true, diary = DiaryState.Ready(DiarySnapshot(diary, 1)), schedule = Schedule(listOf(ScheduleDay(0, "01.01.2026", true, false, listOf(ScheduleLesson("lesson", 1, "UI schedule"))))))
			}
			override fun refresh() { state = state.copy(error = AppError.OFFLINE) }
			override fun logout() { state = NativeUiState() }
			override fun close() { }
		}
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { fake }) } }
			assertTrue("Injected controller did not start", started.await(10, TimeUnit.SECONDS))
			instrumentation.waitForIdleSync()
			waitFor(context.getString(R.string.sign_in))
			edit(editables(instrumentation.uiAutomation.rootInActiveWindow).first { !it.isPassword }, "synthetic")
			waitFor("synthetic")
			edit(editables(instrumentation.uiAutomation.rootInActiveWindow).first { it.isPassword }, "synthetic")
			instrumentation.waitForIdleSync()
			instrumentation.runOnMainSync { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
			instrumentation.waitForIdleSync()
			click(context.getString(R.string.sign_in))
			waitFor(context.getString(R.string.logout))
			assertTrue(fake.secretCleared)
			click(context.getString(R.string.diary)); waitFor("UI lesson")
			click(context.getString(R.string.refresh)); waitFor("No network connection. Check your connection and try again.")
			click(context.getString(R.string.schedule)); waitFor("UI schedule")
			click(context.getString(R.string.login)); click(context.getString(R.string.logout)); waitFor(context.getString(R.string.sign_in))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}
}
