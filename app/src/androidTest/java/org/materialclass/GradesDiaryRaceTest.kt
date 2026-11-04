package org.materialclass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.data.*
import org.materialclass.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GradesDiaryRaceTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private var controller: NativeController? = null
	private var replacement: NativeController? = null
	private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
	private fun await(condition: () -> Boolean) {
		val deadline = android.os.SystemClock.uptimeMillis() + 15000
		while (android.os.SystemClock.uptimeMillis() < deadline) {
			var ready = false
			main { ready = condition() }
			if (ready) return
			Thread.sleep(25)
		}
		fail("Timed out waiting for controller state")
	}
	@After fun cleanup() { main { controller?.close(); replacement?.close() }; coordinator.logout() }

	private fun drain(c: NativeController) {
		val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
		(field.get(c) as java.util.concurrent.ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
		instrumentation.waitForIdleSync()
	}

	private class Gate {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val returned = CountDownLatch(1)
		fun waitHere() { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
	}

	private fun grades(account: String) = AnnualGrades(listOf(AnnualGradeRow("Subject-$account", listOf("Q1-$account", "Q2-$account", "Q3-$account", "Q4-$account"), "Year-$account", "Final-$account")))

	private fun contents(state: NativeUiState): List<String?>? = (state.annualGrades as? GatewayResult.Success)?.value?.rows?.flatMap { listOf(it.subjectName) + it.quarters + listOf(it.yearScore, it.finalScore) }

	private inner class Fixture {
		val diaryGate = AtomicReference<Gate?>(null)
		val gradesGate = AtomicReference<Gate?>(null)
		val profileGate = AtomicReference<Gate?>(null)
		val scheduleGate = AtomicReference<Gate?>(null)
		val scheduleResult = AtomicReference(Schedule(emptyList()))
		val diaryError = AtomicReference<AppError?>(null)
		val gradesError = AtomicReference<AppError?>(null)
		val mismatch = java.util.concurrent.atomic.AtomicBoolean(false)
		val schoolCalls = java.util.concurrent.atomic.AtomicInteger()
		val gateway: SchoolGateway = object : SchoolGateway, DiaryWeeksGateway, AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diaryWeeks(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<DiaryWeeks> { schoolCalls.incrementAndGet(); return GatewayResult.Success(DiaryWeeks("01.01.2026", listOf(DiaryWeekOption("First", "01.01.2026"), DiaryWeekOption("Second", "08.01.2026")))) }
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { schoolCalls.incrementAndGet(); return GatewayResult.Success("01.01.2026") }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				schoolCalls.incrementAndGet()
				diaryGate.getAndSet(null)?.waitHere()
				return diaryError.get()?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success(Diary(context.date, emptyList()))
			}
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
				schoolCalls.incrementAndGet()
				val result = scheduleResult.get()
				scheduleGate.getAndSet(null)?.waitHere()
				return GatewayResult.Success(result)
			}
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> {
				schoolCalls.incrementAndGet()
				val account = session.account
				val gate = gradesGate.getAndSet(null)
				gate?.waitHere()
				val result = gradesError.get()?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success(grades(account))
				gate?.returned?.countDown()
				return result
			}
		}
		val verifier = IdentityVerifier { session, _ ->
			val gate = profileGate.getAndSet(null)
			gate?.waitHere()
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			if (mismatch.get()) profile.put("school_id", 99)
			val result = SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile), if (mismatch.get()) null else StudentProfile("Profile-${session.identityCandidate}", null, null, null))
			gate?.returned?.countDown()
			result
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun login(account: String = "1"): NativeController {
			main { controller = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }; controller!!.login(account, charArrayOf('x')) }
			val c = controller!!
			await { c.state.signedIn && c.state.diary is DiaryState.Ready && c.state.schedule != null && c.state.diaryWeeks.isNotEmpty() }
			return c
		}
	}

	@Test fun diaryRefreshDoesNotCancelBlockedGrades() {
		val fixture = Fixture()
		val c = fixture.login()
		val gate = Gate()
		fixture.gradesGate.set(gate)
		try {
			main { c.refreshAnnualGrades() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { c.selectDiaryWeek("08.01.2026") }
			gate.release.countDown()
			await { !c.state.gradesLoading && c.state.annualGrades is GatewayResult.Success && c.state.diary is DiaryState.Ready && c.state.selectedDiaryDate == "08.01.2026" }
			main { assertEquals("08.01.2026", (c.state.diary as DiaryState.Ready).snapshot.diary.date) }
		} finally { gate.release.countDown() }
	}

	@Test fun gradesRefreshDoesNotCancelBlockedDiaryOrScheduleAndErrorsRemainSeparate() {
		val fixture = Fixture()
		val c = fixture.login()
		val gate = Gate()
		val scheduleGate = Gate()
		val newSchedule = Schedule(emptyList())
		fixture.scheduleResult.set(newSchedule)
		fixture.scheduleGate.set(scheduleGate)
		fixture.diaryGate.set(gate)
		fixture.diaryError.set(AppError.OFFLINE)
		fixture.gradesError.set(AppError.INCOMPATIBLE_RESPONSE)
		try {
			main { c.refresh() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { c.refreshAnnualGrades() }
			gate.release.countDown()
			assertTrue(scheduleGate.entered.await(10, TimeUnit.SECONDS))
			main { assertNotSame(newSchedule, c.state.schedule) }
			scheduleGate.release.countDown()
			await { c.state.schedule === newSchedule && !c.state.gradesLoading && c.state.annualGrades is GatewayResult.Failure && c.state.error == AppError.OFFLINE }
			main { assertEquals(AppError.INCOMPATIBLE_RESPONSE, (c.state.annualGrades as GatewayResult.Failure).error); assertNotNull(c.state.schedule) }
		} finally { gate.release.countDown(); scheduleGate.release.countDown() }
	}

	@Test fun selectingDiaryWeekDoesNotSuppressInFlightSchedule() {
		val fixture = Fixture()
		val c = fixture.login()
		val gate = Gate()
		val newSchedule = Schedule(emptyList())
		fixture.scheduleResult.set(newSchedule)
		fixture.scheduleGate.set(gate)
		try {
			main { c.refresh() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { c.selectDiaryWeek("08.01.2026"); assertNotSame(newSchedule, c.state.schedule) }
			gate.release.countDown()
			drain(c)
			await { c.state.schedule === newSchedule && c.state.diary is DiaryState.Ready && c.state.selectedDiaryDate == "08.01.2026" }
		} finally { gate.release.countDown() }
	}

	private fun replacementRejectsBlockedGrades(oldAccount: String, newAccount: String) {
		val fixture = Fixture()
		val c = fixture.login(oldAccount)
		val gate = Gate()
		val trace = mutableListOf<NativeUiState>()
		fixture.gradesGate.set(gate)
		try {
			main { c.refreshAnnualGrades() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { replacement = NativeController(onState = { trace.add(it) }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) }; replacement!!.login(newAccount, charArrayOf('x')) }
			val b = replacement!!
			await { b.state.signedIn && b.state.diary is DiaryState.Ready }
			main { b.refreshAnnualGrades() }
			val expected = contents(NativeUiState(annualGrades = GatewayResult.Success(grades(newAccount))))
			val forbidden = contents(NativeUiState(annualGrades = GatewayResult.Success(grades(oldAccount))))!!
			await { !b.state.gradesLoading && contents(b.state) == expected }
			gate.release.countDown()
			assertTrue(gate.returned.await(10, TimeUnit.SECONDS))
			drain(c)
			drain(b)
			main {
				assertTrue(b.state.signedIn)
				assertEquals(expected, contents(b.state))
				assertTrue(trace.any { contents(it) == expected })
				trace.forEach { state -> assertTrue("Old account grade in replacement trace", contents(state).orEmpty().none { it in forbidden }) }
				assertFalse(c.state.gradesLoading)
				assertNull(c.state.annualGrades)
			}
		} finally { gate.release.countDown() }
	}

	@Test fun accountReplacementRejectsBlockedGradesDelivery() = replacementRejectsBlockedGrades("1", "2")

	@Test fun reverseAccountReplacementRejectsBlockedGradesDelivery() = replacementRejectsBlockedGrades("2", "1")

	@Test fun logoutRejectsBlockedGradesDelivery() {
		val fixture = Fixture()
		val c = fixture.login()
		val gate = Gate()
		fixture.gradesGate.set(gate)
		try {
			main { c.refreshAnnualGrades() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { c.logout() }
			await { !c.state.logoutPending && !c.state.busy }
			gate.release.countDown()
			assertTrue(gate.returned.await(10, TimeUnit.SECONDS))
			drain(c)
			main { assertFalse(c.state.signedIn); assertFalse(c.state.gradesLoading); assertNull(c.state.annualGrades) }
		} finally { gate.release.countDown() }
	}

	@Test fun contextMismatchClearsPrivateDataAndStopsSchoolCalls() {
		val fixture = Fixture()
		val trace = mutableListOf<NativeUiState>()
		main { controller = NativeController(onState = { trace.add(it) }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) }; controller!!.login("1", charArrayOf('x')) }
		val first = controller!!
		await { first.state.signedIn && first.state.profile?.surname == "Profile-1" && first.state.schedule != null }
		main { first.refreshAnnualGrades() }
		await { contents(first.state)?.contains("Subject-1") == true }
		drain(first)
		val gate = Gate()
		fixture.profileGate.set(gate)
		try {
			main { first.refresh() }
			assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
			main { assertEquals("Profile-1", first.state.profile?.surname); assertTrue(contents(first.state)?.contains("Subject-1") == true) }
			fixture.mismatch.set(true)
			val calls = fixture.schoolCalls.get()
			gate.release.countDown()
			await { first.state.profile == null && first.state.annualGrades == null && first.state.error == AppError.UNAVAILABLE }
			drain(first)
			var clearedAt = 0
			main { clearedAt = trace.size; first.refreshAnnualGrades(); first.refresh() }
			drain(first)
			assertEquals(calls, fixture.schoolCalls.get())
			main { assertTrue(trace.drop(clearedAt).none { it.profile?.surname == "Profile-1" || contents(it)?.contains("Subject-1") == true }) }
		} finally { gate.release.countDown() }
	}

	@Test fun contextMismatchFinishingDuringPendingRevocationCannotRestorePrivateData() {
		val fixture = Fixture()
		val trace = mutableListOf<NativeUiState>()
		main { controller = NativeController(onState = { trace.add(it) }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) }; controller!!.login("1", charArrayOf('x')) }
		val first = controller!!
		await { first.state.signedIn && first.state.profile?.surname == "Profile-1" && first.state.schedule != null }
		main { first.refreshAnnualGrades() }
		await { contents(first.state)?.contains("Subject-1") == true }
		drain(first)
		val held = AtomicReference<(() -> Unit)?>()
		val delivered = CountDownLatch(1)
		main { replacement = NativeController(uiDispatch = { action -> held.set(action); delivered.countDown(); true }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) } }
		val second = replacement!!
		val gate = Gate()
		fixture.profileGate.set(gate)
		val finished = CountDownLatch(1)
		var worker: Thread? = null
		try {
			main { first.refresh() }
			assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
			main { assertEquals("Profile-1", first.state.profile?.surname); assertTrue(contents(first.state)?.contains("Subject-1") == true) }
			fixture.mismatch.set(true)
			worker = kotlin.concurrent.thread { try { coordinator.logout() } finally { finished.countDown() } }
			assertTrue(delivered.await(15, TimeUnit.SECONDS))
			await { !first.state.signedIn && first.state.profile == null && first.state.annualGrades == null }
			assertNotNull(coordinator.pendingLogoutTicket())
			assertNull(coordinator.admit(coordinator.beginLogin()))
			var clearedAt = 0
			main { clearedAt = trace.size }
			val calls = fixture.schoolCalls.get()
			gate.release.countDown()
			assertTrue(gate.returned.await(15, TimeUnit.SECONDS))
			drain(first)
			assertEquals(calls, fixture.schoolCalls.get())
			main { assertTrue(trace.drop(clearedAt).none { it.profile?.surname == "Profile-1" || contents(it)?.contains("Subject-1") == true }); held.get()!!.invoke() }
			assertTrue(finished.await(15, TimeUnit.SECONDS))
			await { coordinator.pendingLogoutTicket() == null }
		} finally {
			gate.release.countDown()
			main { held.get()?.invoke(); second.close(); replacement = null }
			worker?.join(15000)
		}
	}

	@Test fun revocationWaitsForBothControllersAndRejectsLateProfileAndGrades() {
		for (expiry in listOf(false, true)) for (profileRequest in listOf(false, true)) {
			val fixture = Fixture()
			val trace = mutableListOf<NativeUiState>()
			main { controller = NativeController(onState = { trace.add(it) }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) }; controller!!.login("1", charArrayOf('x')) }
			val first = controller!!
			await { first.state.signedIn && first.state.profile?.surname == "Profile-1" && first.state.diary is DiaryState.Ready }
			main { first.refreshAnnualGrades() }
			await { contents(first.state)?.contains("Subject-1") == true }
			val held = AtomicReference<(() -> Unit)?>()
			val delivered = CountDownLatch(1)
			main {
				replacement = NativeController(uiDispatch = { action -> held.set(action); delivered.countDown(); true }) { emit -> SchoolRepository(fixture.gateway, fixture.verifier, coordinator, fixture.cache, { 1L }, emit, SessionStore(context, coordinator)) }
			}
			val second = replacement!!
			val gate = Gate()
			if (profileRequest) fixture.profileGate.set(gate) else fixture.gradesGate.set(gate)
			val finished = CountDownLatch(1)
			var worker: Thread? = null
			try {
				main { if (profileRequest) first.refresh() else first.refreshAnnualGrades() }
				assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
				val active = coordinator.event().session!!
				worker = kotlin.concurrent.thread {
					try { if (expiry) coordinator.revokeIfActive(active, expired = true) else coordinator.logout() } finally { finished.countDown() }
				}
				assertTrue(delivered.await(15, TimeUnit.SECONDS))
				await { !first.state.signedIn && first.state.profile == null && first.state.annualGrades == null }
				assertNotNull(coordinator.pendingLogoutTicket())
				val rejected = charArrayOf('x')
				main { second.login("2", rejected) }
				assertEquals('\u0000', rejected[0])
				assertNull(coordinator.admit(coordinator.beginLogin()))
				var clearedAt = 0
				main { clearedAt = trace.size }
				gate.release.countDown()
				assertTrue(gate.returned.await(15, TimeUnit.SECONDS))
				drain(first)
				main { assertTrue(trace.drop(clearedAt).none { it.profile?.surname == "Profile-1" || contents(it)?.contains("Subject-1") == true }) }
				assertNotNull(coordinator.pendingLogoutTicket())
				main { held.get()!!.invoke() }
				await { coordinator.pendingLogoutTicket() == null }
				assertTrue(finished.await(15, TimeUnit.SECONDS))
				main { second.login("2", charArrayOf('x')) }
				await { second.state.signedIn && second.state.profile?.surname == "Profile-2" }
				main { second.refreshAnnualGrades() }
				await { contents(second.state)?.contains("Subject-2") == true }
				drain(first)
				main { assertTrue(trace.drop(clearedAt).none { it.profile?.surname == "Profile-1" || contents(it)?.contains("Subject-1") == true }) }
			} finally {
				gate.release.countDown()
				main { held.get()?.invoke(); first.close(); second.close(); controller = null; replacement = null }
				worker?.join(15000)
				coordinator.pendingLogoutTicket()?.let { coordinator.logout(it) }
				coordinator.logout()
			}
		}
	}
}
