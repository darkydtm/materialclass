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
	private fun waitFor(text: String): AccessibilityNodeInfo {
		val deadline = SystemClock.uptimeMillis() + 10000
		while (SystemClock.uptimeMillis() < deadline) {
			instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }?.let { return it }
			SystemClock.sleep(100)
		}
		throw AssertionError("Missing node: $text")
	}
	private fun click(text: String) {
		var node = waitFor(text)
		while (!node.isClickable && node.parent != null) node = node.parent
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}
	private fun edit(node: AccessibilityNodeInfo, value: String) {
		val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
	}
	private fun editables(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = (if (node.isEditable) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::editables) ?: emptyList() }

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
			waitFor(context.getString(R.string.operation_error, "OFFLINE"))
			assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Your session has expired. Sign in again.") })
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
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
			click(context.getString(R.string.refresh)); waitFor(context.getString(R.string.operation_error, "OFFLINE"))
			click(context.getString(R.string.schedule)); waitFor("UI schedule")
			click(context.getString(R.string.login)); click(context.getString(R.string.logout)); waitFor(context.getString(R.string.sign_in))
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}
}
