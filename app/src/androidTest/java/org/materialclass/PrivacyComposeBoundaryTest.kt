package org.materialclass

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.Exchange
import org.materialclass.api.NativeGateway
import org.materialclass.api.Request
import org.materialclass.api.Response
import org.materialclass.data.DiaryCache
import org.materialclass.data.DiaryState
import org.materialclass.data.NativeIdentityVerifier
import org.materialclass.data.SchoolRepository
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PrivacyComposeBoundaryTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val profile = """{"userId":123,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}"""

	private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
		if (node.text?.toString() == text) return node
		return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, text) } }
	}

	private fun waitFor(text: String) {
		val deadline = SystemClock.uptimeMillis() + 15000
		while (SystemClock.uptimeMillis() < deadline) {
			if (instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) } != null) return
			SystemClock.sleep(50)
		}
		throw AssertionError("Missing node: $text")
	}

	private fun click(text: String) {
		waitFor(text)
		var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)!!
		while (!node.isClickable && node.parent != null) node = node.parent
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}

	private inner class SyntheticExchange : Exchange {
		val offline = AtomicBoolean(false)
		val block = AtomicBoolean(false)
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		@Volatile var blockedRequest: Request? = null

		override fun execute(request: Request): Response = when (request.url.path) {
			"/api/v2/os/login" -> {
				assertEquals("synthetic-user", JSONObject(String(request.body!!)).getString("login"))
				Response(200, """{"success":true,"access_token":"synthetic-token","user_info":$profile}""")
			}
			"/api/v2/os/user-info" -> Response(200, """{"data":$profile}""")
			"/api/v4/os/clientoffice/diary/weeks" -> Response(200, """{"data":{"week":"01.01.2026","weeks":[{"label":"First","value":"01.01.2026"},{"label":"Second","value":"08.01.2026"}]}}""")
			"/api/v4/os/clientoffice/diary" -> {
				val date = request.url.query.split('&').single { it.startsWith("date=") }.substringAfter('=')
				val failOffline = offline.get()
				if (block.getAndSet(false)) {
					blockedRequest = request
					entered.countDown()
					check(release.await(15, TimeUnit.SECONDS))
				}
				if (failOffline) throw IOException("synthetic offline")
				Response(200, """{"data":{"date":"$date","days":[{"day":"Thursday","date":"$date","subjects":[{"label":"Synthetic lesson","type":"lesson","scheduleUuid":"synthetic","homeworkType":"text","homeworkBody":"Homework for $date","homeworkUuid":null,"hasBooks":false,"hasFiles":false}]}]}}""")
			}
			"/api/v4/os/clientoffice/schedule" -> {
				val dates = listOf("29.12.2025", "30.12.2025", "31.12.2025", "01.01.2026", "02.01.2026", "03.01.2026", "04.01.2026")
				val days = dates.mapIndexed { index, date -> """{"date":$index,"dateFormat":"$date","isCurrent":true,"isHoliday":false,"schedule":[]}""" }.joinToString(",")
				Response(200, """{"data":{"weeks":{"next":"08.01.2026","prev":"25.12.2025","next_time":2,"prev_time":1},"days":[$days]}}""")
			}
			else -> throw AssertionError("Unexpected request: ${request.url.path}")
		}
	}

	@Test fun revokedSessionKeepsLastPresentedPixelsUntilMainResumes() {
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val coordinator = SessionCoordinator(context)
		coordinator.logout()
		val exchange = SyntheticExchange()
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun create() = NativeController { emit -> SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
		val controller = create()
		val other = create()
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		try {
			instrumentation.runOnMainSync { activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { controller }) } }
			waitFor(context.getString(R.string.sign_in))
			val deadline = SystemClock.uptimeMillis() + 15000
			while (true) {
				var busy = true
				instrumentation.runOnMainSync { busy = controller.state.busy }
				if (!busy) break
				check(SystemClock.uptimeMillis() < deadline)
				SystemClock.sleep(50)
			}
			instrumentation.runOnMainSync { controller.login("synthetic-user", "synthetic-password".toCharArray()) }
			waitFor(context.getString(R.string.logout))
			click(context.getString(R.string.diary))
			val secret = "Homework for 01.01.2026"
			waitFor(secret)
			val node = find(instrumentation.uiAutomation.rootInActiveWindow, secret)!!
			val bounds = android.graphics.Rect()
			node.getBoundsInScreen(bounds)
			assertTrue(bounds.width() > 0 && bounds.height() > 0)
			val before = instrumentation.uiAutomation.takeScreenshot()!!
			android.os.Handler(android.os.Looper.getMainLooper()).post {
				entered.countDown()
				release.await(30, TimeUnit.SECONDS)
			}
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			val ticket = coordinator.cancelInFlightNow()
			assertNotNull(coordinator.pendingLogoutTicket())
			assertNull(coordinator.admit(coordinator.beginLogin()))
			val after = instrumentation.uiAutomation.takeScreenshot()!!
			var differences = 0
			for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
				if (before.getPixel(x, y) != after.getPixel(x, y)) differences++
			}
			assertEquals("Last presented secret pixels changed before main resumed", 0, differences)
			assertEquals(secret, node.text.toString())
			before.recycle()
			after.recycle()
			release.countDown()
			waitFor(context.getString(R.string.data_unavailable))
			instrumentation.runOnMainSync {
				assertFalse(controller.state.signedIn)
				assertTrue(controller.state.diary is DiaryState.Cleared)
				assertFalse(other.state.signedIn)
				assertTrue(other.state.diary is DiaryState.Cleared)
			}
			assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, secret) })
			coordinator.logout(ticket)
		} finally {
			release.countDown()
			exchange.release.countDown()
			instrumentation.runOnMainSync { activity.finish(); controller.close(); other.close() }
			coordinator.pendingLogoutTicket()?.let { coordinator.logout(it) }
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}
}
