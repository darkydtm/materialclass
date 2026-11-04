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
	@After fun cleanup() { main { controller?.close() }; coordinator.logout() }

	private class Gate {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		fun waitHere() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
	}

	private inner class Fixture {
		val diaryGate = AtomicReference<Gate?>(null)
		val gradesGate = AtomicReference<Gate?>(null)
		val diaryError = AtomicReference<AppError?>(null)
		val gradesError = AtomicReference<AppError?>(null)
		val gateway: SchoolGateway = object : SchoolGateway, DiaryWeeksGateway, AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diaryWeeks(session: VerifiedDiarySession, lease: RequestLease) = GatewayResult.Success(DiaryWeeks("01.01.2026", listOf(DiaryWeekOption("First", "01.01.2026"), DiaryWeekOption("Second", "08.01.2026"))))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				diaryGate.getAndSet(null)?.waitHere()
				return diaryError.get()?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success(Diary(context.date, emptyList()))
			}
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> {
				gradesGate.getAndSet(null)?.waitHere()
				return gradesError.get()?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Subject", listOf(null, null, null, null), null, ""))))
			}
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun login(): NativeController {
			main { controller = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }; controller!!.login("1", charArrayOf('x')) }
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
		fixture.diaryGate.set(gate)
		fixture.diaryError.set(AppError.OFFLINE)
		fixture.gradesError.set(AppError.INCOMPATIBLE_RESPONSE)
		try {
			main { c.refresh() }
			assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
			main { c.refreshAnnualGrades() }
			gate.release.countDown()
			await { !c.state.gradesLoading && c.state.annualGrades is GatewayResult.Failure && c.state.error == AppError.OFFLINE }
			main { assertEquals(AppError.INCOMPATIBLE_RESPONSE, (c.state.annualGrades as GatewayResult.Failure).error); assertNotNull(c.state.schedule) }
		} finally { gate.release.countDown() }
	}

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
			main { }
			main { assertFalse(c.state.signedIn); assertFalse(c.state.gradesLoading); assertNull(c.state.annualGrades) }
		} finally { gate.release.countDown() }
	}
}
