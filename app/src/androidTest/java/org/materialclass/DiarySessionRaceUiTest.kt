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
class DiarySessionRaceUiTest {
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
			"/api/v4/os/clientoffice/schedule" -> Response(200, """{"data":{"weeks":{"next":"08.01.2026","prev":"25.12.2025","next_time":2,"prev_time":1},"days":[]}}""")
			else -> throw AssertionError("Unexpected request: ${request.url.path}")
		}
	}

	private fun exercise(action: (SyntheticExchange, NativeController) -> Unit) {
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val coordinator = SessionCoordinator(context)
		coordinator.logout()
		val exchange = SyntheticExchange()
		val cache = object : DiaryCache {
			private var value: ByteArray? = null
			override fun read(session: ActiveSession): ByteArray? = value
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { value = bytes; return true }
		}
		val controller = NativeController { emit -> SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
		val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { controller.start(); activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { controller }) } }
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
			waitFor("Homework for 01.01.2026")
			action(exchange, controller)
		} finally {
			exchange.release.countDown()
			instrumentation.runOnMainSync { activity.finish(); controller.close() }
			coordinator.logout()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	private fun drain(controller: NativeController) {
		val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
		(field.get(controller) as java.util.concurrent.ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
		instrumentation.waitForIdleSync()
	}

	@Test fun offlineSelectedWeekUsesOnlyMatchingSavedDiary() = exercise { exchange, controller ->
		click("Second - 08.01.2026")
		waitFor("Homework for 08.01.2026")
		exchange.offline.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		val stale = context.getString(R.string.stale_data, context.getString(R.string.error_offline))
		waitFor(stale)
		waitFor("Selected week: 08.01.2026")
		instrumentation.runOnMainSync {
			val ready = controller.state.diary as DiaryState.Ready
			assertEquals("Homework for 08.01.2026", ready.snapshot.diary.days[0].subjects[0].homeworkBody)
			assertEquals(org.materialclass.api.AppError.OFFLINE, ready.staleError)
			assertEquals("08.01.2026", ready.snapshot.diary.date)
		}
		click("First - 01.01.2026")
		waitFor("Selected week: 01.01.2026")
		waitFor(context.getString(R.string.error_offline))
		drain(controller)
		instrumentation.runOnMainSync { assertTrue(controller.state.diary is DiaryState.Error) }
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Homework for 08.01.2026") })
	}

	@Test fun olderOfflineFailureCannotReplaceNewerSelectedWeek() = exercise { exchange, controller ->
		exchange.offline.set(true)
		exchange.block.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		assertTrue(exchange.entered.await(15, TimeUnit.SECONDS))
		exchange.offline.set(false)
		click("Second - 08.01.2026")
		waitFor("Selected week: 08.01.2026")
		exchange.release.countDown()
		waitFor("Homework for 08.01.2026")
		drain(controller)
		instrumentation.runOnMainSync {
			assertEquals("08.01.2026", controller.state.selectedDiaryDate)
			val ready = controller.state.diary as DiaryState.Ready
			assertEquals("08.01.2026", ready.snapshot.diary.date)
			assertNull(ready.staleError)
			assertNull(controller.state.error)
		}
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Homework for 01.01.2026") })
	}

	@Test fun logoutWhileOfflineDiaryIsPendingCannotRestoreSavedUi() = exercise { exchange, controller ->
		click("Second - 08.01.2026")
		waitFor("Homework for 08.01.2026")
		exchange.offline.set(true)
		exchange.block.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		assertTrue(exchange.entered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { controller.logout() }
		assertTrue(exchange.blockedRequest!!.lease.isCancelled())
		waitFor(context.getString(R.string.data_unavailable))
		exchange.release.countDown()
		drain(controller)
		instrumentation.runOnMainSync {
			assertFalse(controller.state.signedIn)
			assertTrue(controller.state.diary is DiaryState.Cleared)
			assertNull(controller.state.selectedDiaryDate)
			assertTrue(controller.state.diaryWeeks.isEmpty())
		}
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Homework for 08.01.2026") })
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Selected week: 08.01.2026") })
	}
}
