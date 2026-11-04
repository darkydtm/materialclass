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
class ScheduleOfflineRaceTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val controllers = mutableListOf<NativeController>()
	private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
	private fun await(condition: () -> Boolean) {
		val deadline = android.os.SystemClock.uptimeMillis() + 15000
		while (android.os.SystemClock.uptimeMillis() < deadline) {
			var ready = false
			main { ready = condition() }
			if (ready) return
			Thread.sleep(25)
		}
		fail("Timed out waiting for state")
	}
	@After fun cleanup() {
		main { controllers.forEach { it.close() } }
		coordinator.logout()
	}

	private inner class Fixture {
		val error = AtomicReference<AppError?>(null)
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		@Volatile var block = false
		@Volatile var corrupt = false
		@Volatile var bytes: ByteArray? = null
		lateinit var repository: SchoolRepository
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(login.toInt(), 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = error.get()?.let { GatewayResult.Failure(it) } ?: GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule((1..7).map { ScheduleDay(0, "%02d.01.2026".format(it), true, false, listOf(ScheduleLesson("lesson", 1, "Account ${session.account}"))) }))
		}
		val verifier = IdentityVerifier { session, _ ->
			val id = session.identityCandidate!!
			val profile = JSONObject("""{"userId":$id,"role":"student","school_id":$id,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(id), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val diary = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val schedule = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = if (corrupt) byteArrayOf(1, 2, 3) else bytes
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { this@Fixture.bytes = bytes; return true }
		}
		fun create(): NativeController {
			val c = NativeController { emit ->
				SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, emit, SessionStore(context, coordinator), schedule).also { repo ->
					repository = repo
					repo.beforeSavedScheduleDelivery = { if (block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
				}
			}
			controllers.add(c)
			return c
		}
		fun start(): NativeController {
			val c = create()
			main { c.login("1", charArrayOf('x')) }
			await { c.state.scheduleSnapshot != null }
			return c
		}
		fun offline(c: NativeController, reason: AppError = AppError.OFFLINE) {
			error.set(reason)
			main { c.refresh() }
		}
		fun drain(c: NativeController) {
			val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
			val executor = field.get(c) as java.util.concurrent.ExecutorService
			executor.submit {}.get(10, TimeUnit.SECONDS)
			main { }
		}
	}

	@Test fun logoutAfterFallbackCheckDoesNotRestoreSchedule() {
		val f = Fixture()
		val c = f.start()
		f.block = true
		f.offline(c)
		assertTrue(f.entered.await(10, TimeUnit.SECONDS))
		try {
			main { c.logout() }
			await { !c.state.busy }
		} finally { f.release.countDown() }
		f.drain(c)
		main { assertFalse(c.state.signedIn); assertNull(c.state.scheduleSnapshot) }
	}

	@Test fun newerRefreshSuppressesOlderFallback() {
		val f = Fixture()
		val c = f.start()
		f.block = true
		f.offline(c)
		assertTrue(f.entered.await(10, TimeUnit.SECONDS))
		try {
			f.block = false
			f.error.set(null)
			main { c.refresh() }
		} finally { f.release.countDown() }
		f.drain(c)
		await { c.state.scheduleSnapshot?.staleError == null && c.state.scheduleSnapshot != null }
		main { assertFalse(c.state.scheduleSnapshot!!.savedPeriod) }
	}

	@Test fun accountAndSchoolSwitchSuppressesOldFallback() {
		val f = Fixture()
		val old = f.start()
		f.block = true
		f.offline(old)
		assertTrue(f.entered.await(10, TimeUnit.SECONDS))
		val newer = f.create()
		try {
			f.block = false
			f.error.set(null)
			main { newer.login("2", charArrayOf('x')) }
			await { newer.state.scheduleSnapshot != null }
		} finally { f.release.countDown() }
		f.drain(old)
		main {
			assertFalse(old.state.signedIn)
			assertNull(old.state.scheduleSnapshot)
			assertEquals("Account 2", newer.state.schedule!!.days[0].lessons[0].themeLabel)
		}
	}

	@Test fun corruptedSavedScheduleDoesNotAppear() {
		val f = Fixture()
		val c = f.start()
		f.corrupt = true
		f.offline(c)
		await { c.state.scheduleSnapshot == null && c.state.error == AppError.OFFLINE }
	}

	@Test fun timeoutUsesSavedSchedule() {
		val f = Fixture()
		val c = f.start()
		f.offline(c, AppError.TIMEOUT)
		await { c.state.scheduleSnapshot?.staleError == AppError.TIMEOUT }
		main { assertTrue(c.state.scheduleSnapshot!!.savedPeriod); assertTrue(c.state.schedule!!.days.none { it.isCurrent }) }
	}
}
