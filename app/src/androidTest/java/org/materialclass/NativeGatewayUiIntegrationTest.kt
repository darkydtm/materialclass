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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeGatewayUiIntegrationTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private lateinit var activity: MainActivity
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val profile = """{"userId":123,"role":"student","surname":"Synthetic Surname","firstname":"Synthetic Firstname","school_id":1,"group":{"id":2,"name":"Synthetic Group"},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"name":"Synthetic School","eduYears":[{"isCurrent":true,"eduYear":2026}]}}"""
	private val dates = listOf("29.12.2025", "30.12.2025", "31.12.2025", "01.01.2026", "02.01.2026", "03.01.2026", "04.01.2026")
	private val diary = """{"data":{"date":"01.01.2026","days":[{"day":"Thursday","date":"01.01.2026","subjects":[{"label":"Synthetic diary lesson","type":"lesson","scheduleUuid":"synthetic","homeworkType":"none","homeworkBody":null,"homeworkUuid":null,"hasBooks":false,"hasFiles":false}]}]}}"""
	private val schedule = """{"data":{"weeks":{"next":"08.01.2026","prev":"25.12.2025","next_time":2,"prev_time":1},"days":[""" + dates.mapIndexed { index, date -> """{"date":$index,"dateFormat":"$date","isCurrent":true,"isHoliday":false,"schedule":[{"type":"lesson","cabinet":{"id":1},"theme":{"label":"Synthetic schedule lesson"}}]}""" }.joinToString(",") + "]}}"

	private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
		if (node.text?.toString() == text) return node
		return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, text) } }
	}
	private fun waitFor(text: String): AccessibilityNodeInfo {
		val deadline = SystemClock.uptimeMillis() + 15000
		while (SystemClock.uptimeMillis() < deadline) {
			instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }?.let { return it }
			SystemClock.sleep(50)
		}
		throw AssertionError("Missing node: $text")
	}
	private fun click(text: String) {
		var node = waitFor(text)
		while (!node.isClickable && node.parent != null) node = node.parent
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}

	private inner class SyntheticExchange : Exchange {
		val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
		val failLogin = AtomicBoolean(false)
		val failDiary = AtomicBoolean(false)
		val offlineWeeks = AtomicBoolean(false)
		val blockDiary = AtomicBoolean(false)
		val selectableWeeks = AtomicBoolean(false)
		val requestedDates = java.util.Collections.synchronizedList(mutableListOf<String>())
		val cacheWrites = java.util.concurrent.atomic.AtomicInteger()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val returned = CountDownLatch(1)
		@Volatile var blockedRequest: Request? = null
		override fun execute(request: Request): Response {
			paths.add(request.url.path)
			assertEquals("api.bilimclass.kz", request.url.host)
			if (request.url.path != "/api/v2/os/login") {
				assertEquals("Bearer synthetic-token", request.headers["Authorization"])
				assertEquals("1", request.headers["x-school-id"])
			}
			return when (request.url.path) {
				"/api/v2/os/login" -> {
					assertEquals("POST", request.method)
					assertEquals("synthetic-user", JSONObject(String(request.body!!)).getString("login"))
					if (failLogin.get()) Response(503, "") else Response(200, """{"success":true,"access_token":"synthetic-token","user_info":$profile}""")
				}
				"/api/v2/os/user-info" -> Response(200, """{"data":$profile}""")
				"/api/v4/os/clientoffice/diary/weeks" -> if (offlineWeeks.get()) throw java.io.IOException("offline") else if (selectableWeeks.get()) Response(200, """{"data":{"week":"01.01.2026","weeks":[{"label":"First","value":"01.01.2026"},{"label":"Second","value":"08.01.2026"}]}}""") else Response(200, """{"data":{"week":"01.01.2026"}}""")
				"/api/v4/os/clientoffice/diary" -> {
					val date = request.url.query.split('&').single { it.startsWith("date=") }.substringAfter('=')
					requestedDates.add(date)
					if (blockDiary.get()) {
						blockedRequest = request
						entered.countDown()
						check(release.await(15, TimeUnit.SECONDS))
						returned.countDown()
					}
					if (failDiary.get()) Response(503, "") else if (selectableWeeks.get()) Response(200, diary.replace("01.01.2026", date).replace("null,\"homeworkUuid\"", "\"Homework for $date\",\"homeworkUuid\"")) else Response(200, diary)
				}
				"/api/v4/os/clientoffice/schedule" -> Response(200, schedule)
				else -> throw AssertionError("Unexpected request: ${request.url.path}")
			}
		}
	}

	private fun exercise(action: (SyntheticExchange, NativeController) -> Unit) {
		context.getSharedPreferences("appearance", 0).edit().putBoolean("warning_seen", true).putString("language", "en").commit()
		val coordinator = SessionCoordinator(context)
		coordinator.logout()
		val exchange = SyntheticExchange()
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { exchange.cacheWrites.incrementAndGet(); return true }
		}
		val scheduleCache = org.materialclass.data.EncryptedDiaryCache(org.materialclass.cache.EncryptedCache(context, coordinator, "schedule"))
		val controller = NativeController { emit -> SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, emit, SessionStore(context, coordinator), scheduleCache) }
		activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { controller.start(); activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { controller }) } }
			waitFor(context.getString(R.string.sign_in))
			val deadline = SystemClock.uptimeMillis() + 15000
			while (true) {
				var ready = false
				instrumentation.runOnMainSync { ready = !controller.state.busy }
				if (ready) break
				check(SystemClock.uptimeMillis() < deadline) { "Restore did not complete" }
				SystemClock.sleep(50)
			}
			instrumentation.waitForIdleSync()
			action(exchange, controller)
		} finally {
			exchange.release.countDown()
			instrumentation.runOnMainSync { activity.finish(); controller.close() }
			coordinator.logout()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	private fun signIn(controller: NativeController) {
		instrumentation.runOnMainSync { controller.login("synthetic-user", "synthetic-password".toCharArray()) }
	}


	@Test fun verifiedProfileDisplaysObservedFields() = exercise { _, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click("Profile")
		waitFor("Synthetic Surname")
		waitFor("Synthetic Firstname")
		waitFor("Synthetic School")
		waitFor("Synthetic Group")
	}

	@Test fun offlineWeekShowsSavedPeriodInActualUi() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.schedule))
		waitFor("Synthetic schedule lesson")
		exchange.offlineWeeks.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		waitFor(context.getString(R.string.saved_period))
		waitFor("29.12.2025")
		waitFor("29.12.2025 - 04.01.2026")
		var snapshot: org.materialclass.data.ScheduleSnapshot? = null
		instrumentation.runOnMainSync { snapshot = controller.state.scheduleSnapshot }
		assertTrue(snapshot!!.savedPeriod)
		assertTrue(snapshot!!.schedule.days.none { it.isCurrent })
	}

	@Test fun selectingDiaryWeekUpdatesHomework() = exercise { exchange, controller ->
		exchange.selectableWeeks.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Synthetic diary lesson")
		click("Second - 08.01.2026")
		waitFor("Selected week: 08.01.2026")
		waitFor("Homework for 08.01.2026")
		click(context.getString(R.string.homework))
		waitFor("Selected week: 08.01.2026")
		waitFor("Homework for 08.01.2026")
		assertTrue(exchange.requestedDates.contains("08.01.2026"))
	}

	@Test fun laterWeekSelectionWinsAfterDelayedResponse() = exercise { exchange, controller ->
		exchange.selectableWeeks.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Homework for 01.01.2026")
		click("Second - 08.01.2026")
		waitFor("Homework for 08.01.2026")
		exchange.blockDiary.set(true)
		click("First - 01.01.2026")
		assertTrue(exchange.entered.await(15, TimeUnit.SECONDS))
		waitFor("Selected week: 01.01.2026")
		click("Second - 08.01.2026")
		waitFor("Selected week: 08.01.2026")
		exchange.blockDiary.set(false)
		exchange.release.countDown()
		assertTrue(exchange.returned.await(15, TimeUnit.SECONDS))
		val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
		(field.get(controller) as java.util.concurrent.ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
		waitFor("Homework for 08.01.2026")
		instrumentation.waitForIdleSync()
		instrumentation.runOnMainSync {
			assertEquals("08.01.2026", controller.state.selectedDiaryDate)
			assertEquals("08.01.2026", (controller.state.diary as DiaryState.Ready).snapshot.diary.date)
		}
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Homework for 01.01.2026") })
	}

	@Test fun invalidWeekSelectionIsIgnored() = exercise { exchange, controller ->
		exchange.selectableWeeks.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Homework for 01.01.2026")
		val before = exchange.requestedDates.size
		instrumentation.runOnMainSync { controller.selectDiaryWeek("15.01.2026") }
		instrumentation.waitForIdleSync()
		instrumentation.runOnMainSync { assertEquals("01.01.2026", controller.state.selectedDiaryDate) }
		assertEquals(before, exchange.requestedDates.size)
	}

	@Test fun logoutDuringWeekSelectionRejectsLateUiAndCacheWrite() = exercise { exchange, controller ->
		exchange.selectableWeeks.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Homework for 01.01.2026")
		val writes = exchange.cacheWrites.get()
		exchange.blockDiary.set(true)
		click("Second - 08.01.2026")
		assertTrue(exchange.entered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { controller.logout() }
		click(context.getString(R.string.login))
		waitFor(context.getString(R.string.sign_in))
		exchange.release.countDown()
		assertTrue(exchange.returned.await(15, TimeUnit.SECONDS))
		val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
		(field.get(controller) as java.util.concurrent.ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
		instrumentation.waitForIdleSync()
		instrumentation.runOnMainSync {
			assertFalse(controller.state.signedIn)
			assertTrue(controller.state.diary is DiaryState.Cleared)
			assertNull(controller.state.selectedDiaryDate)
		}
		assertEquals(writes, exchange.cacheWrites.get())
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Homework for 08.01.2026") })
	}

	@Test fun realGatewayDisplaysDiaryScheduleAndLogout() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Synthetic diary lesson")
		click(context.getString(R.string.schedule))
		waitFor("Synthetic schedule lesson")
		assertTrue(exchange.paths.containsAll(listOf("/api/v2/os/login", "/api/v2/os/user-info", "/api/v4/os/clientoffice/diary/weeks", "/api/v4/os/clientoffice/diary", "/api/v4/os/clientoffice/schedule")))
		click(context.getString(R.string.login))
		click(context.getString(R.string.logout))
		waitFor(context.getString(R.string.sign_in))
	}

	@Test fun httpFailuresAreVisibleAndLoginCanRetry() = exercise { exchange, controller ->
		exchange.failLogin.set(true)
		signIn(controller)
		waitFor("The service is unavailable. Try again later.")
		exchange.failLogin.set(false)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Synthetic diary lesson")
		exchange.failDiary.set(true)
		click(context.getString(R.string.refresh))
		waitFor("The service is unavailable. Try again later.")
	}

	@Test fun logoutCancelsBlockedDiaryAndRejectsLateResult() = exercise { exchange, controller ->
		exchange.blockDiary.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		assertTrue(exchange.entered.await(15, TimeUnit.SECONDS))
		click(context.getString(R.string.logout))
		waitFor(context.getString(R.string.sign_in))
		assertTrue(exchange.blockedRequest!!.lease.isCancelled())
		exchange.release.countDown()
		assertTrue(exchange.returned.await(15, TimeUnit.SECONDS))
		val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
		(field.get(controller) as java.util.concurrent.ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
		instrumentation.waitForIdleSync()
		instrumentation.runOnMainSync {
			assertFalse(controller.state.signedIn)
			assertTrue(controller.state.diary is DiaryState.Cleared)
			assertNull(controller.state.schedule)
		}
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic diary lesson") })
	}
}
