package org.materialclass

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
		val blockGrades = AtomicBoolean(false)
		val offlineGrades = AtomicBoolean(false)
		val gradesEntered = CountDownLatch(1)
		val gradesRelease = CountDownLatch(1)
		val gradesCalls = java.util.concurrent.atomic.AtomicInteger()
		val selectableWeeks = AtomicBoolean(false)
		val requestedDates = java.util.Collections.synchronizedList(mutableListOf<String>())
		val cacheWrites = java.util.concurrent.atomic.AtomicInteger()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val returned = CountDownLatch(1)
		val blockProfile = AtomicBoolean(false)
		val profileEntered = CountDownLatch(1)
		val profileRelease = CountDownLatch(1)
		val profileReturned = CountDownLatch(1)
		@Volatile var profileResponse = profile
		@Volatile var loginProfile = profile
		@Volatile var blockedProfile: Request? = null
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
					assertEquals("synthetic-password", JSONObject(String(request.body!!)).getString("password"))
					if (failLogin.get()) Response(503, "") else Response(200, """{"success":true,"access_token":"synthetic-token","user_info":$loginProfile}""")
				}
				"/api/v2/os/user-info" -> {
					val response = profileResponse
					if (blockProfile.get()) {
						blockedProfile = request
						profileEntered.countDown()
						check(profileRelease.await(30, TimeUnit.SECONDS))
						profileReturned.countDown()
					}
					Response(200, """{"data":$response}""")
				}
				"/api/v4/os/clientoffice/diary/year" -> {
					val number = gradesCalls.incrementAndGet()
					if (offlineGrades.get()) throw java.io.IOException("offline")
					if (blockGrades.get() && number == 1) { gradesEntered.countDown(); check(gradesRelease.await(60, TimeUnit.SECONDS)) }
					val subject = if (blockGrades.get() && number == 1) "Old annual subject" else "Synthetic annual subject"
					Response(200, """{"data":{"rows":[{"subjectName":"$subject","attestations":{"quarter1":"4","quarter2":null,"quarter3":"0"},"yearScores":{"yearScore":null,"finalScore":""}}]}}""")
				}

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
		val gradesCache = org.materialclass.data.EncryptedDiaryCache(org.materialclass.cache.EncryptedCache(context, coordinator, "grades"))
		val controller = NativeController { emit -> SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, emit, SessionStore(context, coordinator), scheduleCache, gradesCache = gradesCache) }
		activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
		try {
			instrumentation.runOnMainSync { controller.start(); activity.setContent { MaterialClassApp(nativeEnabled = true, controllerFactory = { controller }) } }
			instrumentation.waitForIdleSync()
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
			exchange.gradesRelease.countDown()
			exchange.profileRelease.countDown()

			instrumentation.runOnMainSync { activity.finish(); controller.close() }
			coordinator.logout()
			context.getSharedPreferences("appearance", 0).edit().clear().commit()
		}
	}

	private fun signIn(controller: NativeController) {
		instrumentation.runOnMainSync { controller.login("synthetic-user", "synthetic-password".toCharArray()) }
	}

	private fun editables(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
		(if (node.isEditable) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::editables) ?: emptyList() }

	private fun enterText(password: Boolean, value: String) {
		val deadline = SystemClock.uptimeMillis() + 15000
		val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
		while (SystemClock.uptimeMillis() < deadline) {
			val root = instrumentation.uiAutomation.rootInActiveWindow
			val node = root?.let(::editables)?.firstOrNull { it.isPassword == password && it.isEnabled }
			if (node != null && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
				node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
				if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
			}
			SystemClock.sleep(50)
		}
		throw AssertionError("Unable to enter text in ${if (password) "password" else "username"} field")
	}

	@Test fun studentJourneyUsesLoginFormAndVisibleModuleActions() = exercise { exchange, _ ->
		exchange.selectableWeeks.set(true)
		val fields = editables(instrumentation.uiAutomation.rootInActiveWindow)
		assertEquals(2, fields.size)
		enterText(false, "synthetic-user")
		val deadline = SystemClock.uptimeMillis() + 15000
		while (true) {
			val root = instrumentation.uiAutomation.rootInActiveWindow
			val username = root?.let(::editables)?.firstOrNull { !it.isPassword }?.text?.toString().orEmpty()
			if (username.contains("synthetic-user")) break
			check(SystemClock.uptimeMillis() < deadline) { "Username was not retained in the login field: $username" }
			SystemClock.sleep(50)
		}
		enterText(true, "synthetic-password")
		instrumentation.waitForIdleSync()
		instrumentation.runOnMainSync { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
		instrumentation.waitForIdleSync()
		click(context.getString(R.string.sign_in))
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.diary))
		waitFor("Homework for 01.01.2026")
		click(context.getString(R.string.homework))
		waitFor("Homework for 01.01.2026")
		click(context.getString(R.string.schedule))
		waitFor("Synthetic schedule lesson")
		click(context.getString(R.string.login))
		click(context.getString(R.string.profile))
		waitFor("Synthetic Surname")
		click(context.getString(R.string.login))
		click(context.getString(R.string.grades))
		click(context.getString(R.string.grades_load))
		waitFor("Synthetic annual subject")
		click(context.getString(R.string.login))
		click(context.getString(R.string.logout))
		waitFor(context.getString(R.string.sign_in))
		assertTrue(exchange.paths.containsAll(listOf("/api/v2/os/login", "/api/v2/os/user-info", "/api/v4/os/clientoffice/diary/weeks", "/api/v4/os/clientoffice/diary", "/api/v4/os/clientoffice/schedule", "/api/v4/os/clientoffice/diary/year")))
	}


	@Test fun annualGradesReachVisibleUiThroughGatewayAndRepository() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		instrumentation.runOnMainSync { controller.refreshAnnualGrades() }
		val deadline = SystemClock.uptimeMillis() + 15000
		while (true) {
			var ready = false
			instrumentation.runOnMainSync { ready = controller.state.annualGrades is org.materialclass.api.GatewayResult.Success }
			if (ready) break
			check(SystemClock.uptimeMillis() < deadline)
			SystemClock.sleep(50)
		}
		click(context.getString(R.string.grades))
		waitFor("Synthetic annual subject")
		waitFor("Quarter 1: 4")
		waitFor("Quarter 2: Unknown")
		waitFor("Quarter 3: 0")
		waitFor("Final: Unavailable")
		assertTrue(exchange.paths.contains("/api/v4/os/clientoffice/diary/year"))
	}

	@Test fun offlineAnnualGradesShowSavedTimestampInVisibleUi() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click(context.getString(R.string.grades))
		click(context.getString(R.string.grades_load))
		waitFor("Synthetic annual subject")
		exchange.offlineGrades.set(true)
		click(context.getString(R.string.grades_load))
		waitFor(context.getString(R.string.stale_data, context.getString(R.string.error_offline)))
		val formatted = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.ENGLISH).format(java.util.Date(1L))
		waitFor(context.getString(R.string.last_updated, formatted))
		waitFor("Synthetic annual subject")
		instrumentation.runOnMainSync {
			assertEquals(1L, controller.state.gradesLastSuccessMillis)
			assertEquals(org.materialclass.api.AppError.OFFLINE, controller.state.gradesStaleError)
		}
	}

	@Test fun latestPendingAnnualRequestWinsInVisibleUi() = exercise { exchange, controller ->
		exchange.blockGrades.set(true)
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		instrumentation.runOnMainSync { controller.refreshAnnualGrades() }
		assertTrue(exchange.gradesEntered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { controller.refreshAnnualGrades(); assertTrue(controller.state.gradesLoading) }
		exchange.gradesRelease.countDown()
		click(context.getString(R.string.grades))
		waitFor("Synthetic annual subject")
		instrumentation.waitForIdleSync()
		assertEquals(2, exchange.gradesCalls.get())
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Old annual subject") })
		instrumentation.runOnMainSync { assertFalse(controller.state.gradesLoading) }
	}

	private fun drain(controller: NativeController, name: String = "executor") {
		val field = NativeController::class.java.getDeclaredField(name).apply { isAccessible = true }
		(field.get(controller) as java.util.concurrent.ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
		instrumentation.waitForIdleSync()
	}

	@Test fun pendingProfileKeepsGradesDiaryAndScheduleAvailable() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		drain(controller)
		exchange.blockProfile.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		assertTrue(exchange.profileEntered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { controller.refreshAnnualGrades(); assertTrue(controller.state.gradesLoading) }
		exchange.profileRelease.countDown()
		drain(controller)
		instrumentation.runOnMainSync {
			assertTrue(controller.state.signedIn)
			assertNotNull(controller.state.profile)
			assertTrue(controller.state.diary is DiaryState.Ready)
			assertNotNull(controller.state.schedule)
			assertTrue(controller.state.annualGrades is org.materialclass.api.GatewayResult.Success)
			assertFalse(controller.state.gradesLoading)
		}
	}

	@Test fun delayedProfileAfterLogoutCannotRestoreIdentity() = exercise { exchange, controller ->
		exchange.blockProfile.set(true)
		signIn(controller)
		assertTrue(exchange.profileEntered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { controller.logout(); assertFalse(controller.state.signedIn); assertNull(controller.state.profile) }
		drain(controller, "logoutExecutor")
		assertTrue(exchange.blockedProfile!!.lease.isCancelled())
		exchange.profileRelease.countDown()
		assertTrue(exchange.profileReturned.await(15, TimeUnit.SECONDS))
		drain(controller)
		instrumentation.runOnMainSync { assertFalse(controller.state.signedIn); assertNull(controller.state.profile) }
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic Surname") })
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic School") })
	}

	@Test fun mismatchedProfileCannotExposeUnverifiedFields() = exercise { exchange, controller ->
		exchange.profileResponse = profile.replace("\"userId\":123", "\"userId\":124")
		signIn(controller)
		drain(controller)
		instrumentation.runOnMainSync { assertFalse(controller.state.signedIn); assertNull(controller.state.profile) }
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic Surname") })
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic School") })
	}

	@Test fun mismatchedContextCannotExposeProfile() = exercise { exchange, controller ->
		exchange.profileResponse = profile.replace("\"school_id\":1", "\"school_id\":2")
		signIn(controller)
		drain(controller)
		instrumentation.runOnMainSync { assertTrue(controller.state.signedIn); assertNull(controller.state.profile) }
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic Surname") })
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic School") })
	}

	private fun secondController(exchange: SyntheticExchange): NativeController {
		val coordinator = SessionCoordinator(context)
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		return NativeController { emit -> SchoolRepository(NativeGateway(exchange), NativeIdentityVerifier(exchange), coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
	}

	@Test fun delayedAccountAProfileCannotAppearInAccountB() = exercise { exchange, first ->
		signIn(first)
		waitFor(context.getString(R.string.logout))
		click("Profile")
		waitFor("Synthetic Surname")
		drain(first)
		exchange.profileResponse = profile.replace("Synthetic", "Stale")
		exchange.blockProfile.set(true)
		instrumentation.runOnMainSync { first.refresh() }
		assertTrue("Refresh must request the verified profile", exchange.profileEntered.await(15, TimeUnit.SECONDS))
		instrumentation.runOnMainSync { first.logout(); assertNull(first.state.profile) }
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Stale Surname") })
		drain(first, "logoutExecutor")
		val second = secondController(exchange)
		val snapshots = mutableListOf<NativeUiState>()
		try {
			exchange.blockProfile.set(false)
			exchange.loginProfile = profile.replace("123", "456").replace("Synthetic", "Second")
			exchange.profileResponse = exchange.loginProfile
			instrumentation.runOnMainSync {
				second.start()
				activity.setContent {
					val snapshot = second.state
					androidx.compose.runtime.SideEffect { snapshots.add(snapshot) }
					androidx.compose.runtime.key(second) { MaterialClassApp(nativeEnabled = true, controllerFactory = { second }) }
				}
			}
			instrumentation.waitForIdleSync()
			drain(second)
			instrumentation.runOnMainSync { second.login("synthetic-user", "synthetic-password".toCharArray()) }
			waitFor(context.getString(R.string.logout))
			click("Profile")
			waitFor("Second Surname")
			waitFor("Second School")
			instrumentation.runOnMainSync {
				assertTrue(snapshots.any { it.profile?.surname == "Second Surname" && it.profile?.school == "Second School" })
				assertTrue(exchange.blockedProfile!!.lease.isCancelled())
			}
			exchange.profileRelease.countDown()
			assertTrue(exchange.profileReturned.await(15, TimeUnit.SECONDS))
			val firstExecutor = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(first) as java.util.concurrent.ExecutorService
			assertTrue(firstExecutor.awaitTermination(30, TimeUnit.SECONDS))
			drain(second)
			instrumentation.runOnMainSync {
				assertEquals("Second Surname", second.state.profile?.surname)
				assertEquals("Second School", second.state.profile?.school)
				assertTrue(snapshots.isNotEmpty())
				assertTrue(snapshots.none { it.profile?.surname == "Synthetic Surname" || it.profile?.school == "Synthetic School" || it.profile?.surname == "Stale Surname" || it.profile?.school == "Stale School" })
			}
			assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic Surname") })
			assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic School") })
		} finally { exchange.profileRelease.countDown(); instrumentation.runOnMainSync { second.close() } }
	}

	private fun contradictoryRefresh(response: String) = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click("Profile")
		waitFor("Synthetic Surname")
		drain(controller)
		instrumentation.runOnMainSync {
			assertTrue(controller.state.diary is DiaryState.Ready)
			assertNotNull(controller.state.schedule)
		}
		exchange.profileResponse = response
		instrumentation.runOnMainSync { controller.refresh() }
		waitFor(context.getString(R.string.session_expired))
		drain(controller)
		instrumentation.runOnMainSync {
			assertFalse(controller.state.signedIn)
			assertNull(controller.state.profile)
			assertNull(controller.state.schedule)
			assertNull(controller.state.scheduleSnapshot)
			assertTrue(controller.state.diary is DiaryState.Cleared)
			assertTrue(controller.state.diaryWeeks.isEmpty())
		}
		click(context.getString(R.string.login))
		waitFor(context.getString(R.string.sign_in))
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic Surname") })
		assertNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, "Synthetic School") })
		assertFalse(java.io.File(context.noBackupFilesDir, "session").exists())
		assertTrue(java.io.File(context.noBackupFilesDir, "session-revoked").exists())
	}

	private fun rejectChangedContext(response: String) = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		drain(controller)
		instrumentation.runOnMainSync { controller.refreshAnnualGrades() }
		drain(controller)
		instrumentation.runOnMainSync {
			assertTrue(controller.state.diary is DiaryState.Ready)
			assertNotNull(controller.state.schedule)
			assertNotNull(controller.state.annualGrades)
		}
		val before = synchronized(exchange.paths) { exchange.paths.count { it.startsWith("/api/v4/") } }
		exchange.profileResponse = response
		instrumentation.runOnMainSync { controller.refresh() }
		drain(controller)
		instrumentation.runOnMainSync { controller.refreshAnnualGrades() }
		drain(controller)
		instrumentation.runOnMainSync {
			assertNull(controller.state.profile)
			assertFalse(controller.state.diary is DiaryState.Ready)
			assertNull(controller.state.schedule)
			assertNull(controller.state.selectedDiaryDate)
			assertTrue(controller.state.diaryWeeks.isEmpty())
			assertFalse(controller.state.annualGrades is org.materialclass.api.GatewayResult.Success)
			assertFalse(controller.state.gradesLoading)
		}
		assertEquals(before, synchronized(exchange.paths) { exchange.paths.count { it.startsWith("/api/v4/") } })
	}

	@Test fun changedSchoolContextCannotReuseVerifiedProofs() = rejectChangedContext(profile.replace("\"school_id\":1", "\"school_id\":9"))
	@Test fun changedGroupContextCannotReuseVerifiedProofs() = rejectChangedContext(profile.replace("\"id\":2", "\"id\":9"))
	@Test fun changedYearContextCannotReuseVerifiedProofs() = rejectChangedContext(profile.replace("\"eduYear\":2026", "\"eduYear\":2027"))
	@Test fun changedUuidCannotReuseVerifiedProofs() = rejectChangedContext(profile.replace("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"))
	@Test fun missingProfileCannotReuseVerifiedProofs() = rejectChangedContext("{}")
	@Test fun unknownRoleCannotReuseVerifiedProofs() = rejectChangedContext(profile.replace("\"student\"", "\"future-role\""))

	@Test fun newerProfileRefreshRejectsEarlierResponse() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		drain(controller)
		exchange.profileResponse = profile.replace("Synthetic", "Old")
		exchange.blockProfile.set(true)
		instrumentation.runOnMainSync { controller.refresh() }
		assertTrue(exchange.profileEntered.await(15, TimeUnit.SECONDS))
		exchange.profileResponse = profile.replace("Synthetic", "New")
		instrumentation.runOnMainSync { controller.refresh() }
		exchange.blockProfile.set(false)
		exchange.profileRelease.countDown()
		drain(controller)
		instrumentation.runOnMainSync {
			assertEquals("New Surname", controller.state.profile?.surname)
			assertTrue(controller.state.diary is DiaryState.Ready)
			assertNotNull(controller.state.schedule)
			assertFalse(controller.state.gradesLoading)
		}
	}

	@Test fun refreshedWrongRoleRevokesSession() = contradictoryRefresh(profile.replace("\"student\"", "\"teacher\""))
	@Test fun refreshedWrongIdRevokesSession() = contradictoryRefresh(profile.replace("\"userId\":123", "\"userId\":124"))

	@Test fun refreshDisplaysNewVerifiedProfile() = exercise { exchange, controller ->
		signIn(controller)
		waitFor(context.getString(R.string.logout))
		click("Profile")
		waitFor("Synthetic Surname")
		drain(controller)
		exchange.profileResponse = profile.replace("Synthetic", "Updated")
		instrumentation.runOnMainSync { controller.refresh() }
		waitFor("Updated Surname")
		waitFor("Updated School")
		instrumentation.runOnMainSync { assertEquals("Updated Surname", controller.state.profile?.surname) }
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
