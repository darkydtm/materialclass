package org.materialclass

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityUiTest {
	private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
		if (node.text?.toString() == text) return node
		return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, text) } }
	}

	private fun editableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
		(if (node.isEditable) listOf(node) else emptyList()) +
			(0 until node.childCount).flatMap { node.getChild(it)?.let { child -> editableNodes(child) } ?: emptyList() }

	private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
		var current = node
		while (!current.isClickable && current.parent != null) current = current.parent
		return current
	}

	private fun selectedAncestor(node: AccessibilityNodeInfo): Boolean {
		var current: AccessibilityNodeInfo? = node
		while (current != null) {
			if (current.isSelected || current.isChecked) return true
			current = current.parent
		}
		return false
	}

	private fun waitFor(text: String): AccessibilityNodeInfo {
		val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
		val deadline = SystemClock.uptimeMillis() + 10000
		while (SystemClock.uptimeMillis() < deadline) {
			automation.rootInActiveWindow?.let { find(it, text) }?.let { return it }
			SystemClock.sleep(100)
		}
		throw AssertionError("Missing accessibility node: $text")
	}

	@Test
	fun passwordRequestsPasswordKeyboardAndNavigationExposesSelection() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("appearance", 0).edit().clear().putBoolean("warning_seen", true).commit()
		val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		try {
			waitFor(context.getString(R.string.password))
			val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
			val password = editableNodes(root).single { it.isPassword }
			assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
			SystemClock.sleep(500)
			val info = EditorInfo()
			InstrumentationRegistry.getInstrumentation().runOnMainSync {
				activity.window.decorView.findFocus().onCreateInputConnection(info)
			}
			assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
			InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
			SystemClock.sleep(500)
			val diary = clickable(waitFor(context.getString(R.string.diary)))
			assertTrue(diary.performAction(AccessibilityNodeInfo.ACTION_CLICK))
			val deadline = SystemClock.uptimeMillis() + 5000
			var selected = false
			while (!selected && SystemClock.uptimeMillis() < deadline) {
				val rootAfterClick = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
				selected = rootAfterClick?.let { find(it, context.getString(R.string.diary)) }?.let { selectedAncestor(it) } == true
				if (!selected) SystemClock.sleep(100)
			}
			assertTrue("Current navigation item must be selected", selected)
		} finally {
			activity.finish()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}
}