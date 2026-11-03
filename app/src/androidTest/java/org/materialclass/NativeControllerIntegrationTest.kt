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

@RunWith(AndroidJUnit4::class)
class NativeControllerIntegrationTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private var controller: NativeController? = null
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
	private fun await(condition: () -> Boolean) {
		val deadline = android.os.SystemClock.uptimeMillis() + 15000
		while (android.os.SystemClock.uptimeMillis() < deadline) {
			var ready = false
			main { ready = condition() }
			if (ready) return
			Thread.sleep(50)
		}
		fail("Timed out waiting for controller state")
	}
	@After fun cleanup() {
		main { controller?.close() }
		coordinator.logout()
	}

	@Test fun oldLogoutCompletionAfterActivationUnblocksSecondController() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val blocked = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val shared = SessionCoordinator { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.UNAVAILABLE)
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun create() = NativeController { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, cache, { 1L }, emit, SessionStore(context, shared)) }
		main { controller = create() }
		val first = controller!!
		val second = create()
		val field = NativeController::class.java.getDeclaredField("logoutExecutor").apply { isAccessible = true }
		val executor = field.get(second) as java.util.concurrent.ExecutorService
		executor.execute { blocked.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
		try {
			assertTrue(blocked.await(10, TimeUnit.SECONDS))
			main { first.logout() }
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			main { second.logout(); assertTrue(second.state.busy) }
			release.countDown()
			await { !first.state.busy && shared.pendingLogoutTicket() == null }
			val current = shared.activate(AccountId("synthetic-new"))
			val observerField = NativeController::class.java.getDeclaredField("observeLogout").apply { isAccessible = true }
			@Suppress("UNCHECKED_CAST")
			val observer = observerField.get(second) as (Long) -> Unit
			main { observer(shared.revocationEpoch()); assertTrue(second.state.busy) }
			resume.countDown()
			await { !second.state.busy && !second.state.logoutPending }
			assertEquals("current", shared.ifActive(current) { "current" })
		} finally { release.countDown(); resume.countDown(); main { second.close() }; shared.pendingLogoutTicket()?.let { shared.logout(it) }; shared.logout() }
	}

	@Test fun expiryDuringIndependentLoginKeepsAttemptAndClearsOldData() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val shared = SessionCoordinator { }
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				if (login == "2") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
				return GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			}
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun create() = NativeController { emit -> SchoolRepository(gateway, verifier, shared, cache, { 1L }, emit, SessionStore(context, shared)) }
		main { controller = create() }
		val first = controller!!
		val second = create()
		try {
			main { first.login("1", charArrayOf('x')) }
			await { first.state.signedIn && first.state.diary is DiaryState.Ready }
			val old = shared.event().session!!
			main { second.login("2", charArrayOf('x')) }
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertTrue(shared.revokeIfActive(old, expired = true))
			await { !first.state.signedIn && first.state.error == AppError.SESSION_EXPIRED }
			main { assertTrue(first.state.diary is DiaryState.Cleared); assertNull(first.state.schedule); assertTrue(second.state.busy); assertFalse(second.state.signedIn) }
			release.countDown()
			await { second.state.signedIn && second.state.diary is DiaryState.Ready }
		} finally { release.countDown(); main { second.close() }; shared.logout() }
	}

	@Test fun failedReplacementClearsBothControllersAndCanRetry() {
		val failing = java.util.concurrent.atomic.AtomicBoolean(false)
		val shared = SessionCoordinator { if (failing.get()) throw IllegalStateException("synthetic failure") }
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun create() = NativeController { emit -> SchoolRepository(gateway, verifier, shared, cache, { 1L }, emit, SessionStore(context, shared)) }
		main { controller = create() }
		val first = controller!!
		val second = create()
		try {
			main { first.login("1", charArrayOf('x')) }
			await { first.state.signedIn && first.state.diary is DiaryState.Ready }
			failing.set(true)
			main { second.login("2", charArrayOf('x')) }
			await { first.state.logoutPending && second.state.logoutPending && !second.state.busy }
			main { assertTrue(first.state.diary is DiaryState.Cleared); assertNull(first.state.schedule); assertFalse(second.state.signedIn) }
			val rejected = charArrayOf('x')
			main { second.login("2", rejected) }
			assertEquals('\u0000', rejected[0])
			failing.set(false)
			main { second.logout() }
			await { !second.state.logoutPending && !second.state.busy }
			main { second.login("2", charArrayOf('x')) }
			await { second.state.signedIn && second.state.diary is DiaryState.Ready }
		} finally { failing.set(false); main { second.close() }; shared.pendingLogoutTicket()?.let { shared.logout(it) }; shared.logout() }
	}

	@Test fun accountSwitchClearsOtherControllerAndExpiryRemainsVisible() {
		val expire = java.util.concurrent.atomic.AtomicBoolean(false)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (expire.get()) GatewayResult.Failure(AppError.SESSION_EXPIRED) else GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		fun create() = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
		main { controller = create() }
		val first = controller!!
		val second = create()
		try {
			main { first.login("1", charArrayOf('x')) }
			await { first.state.signedIn && first.state.diary is DiaryState.Ready }
			main { second.login("2", charArrayOf('x')) }
			await { second.state.signedIn && second.state.diary is DiaryState.Ready && !first.state.signedIn }
			main { assertTrue(first.state.diary is DiaryState.Cleared); assertNull(first.state.schedule) }
			expire.set(true)
			main { second.refresh() }
			await { !second.state.signedIn && second.state.error == AppError.SESSION_EXPIRED }
		} finally { main { second.close() } }
	}

	@Test fun overlappingLogoutCompletionKeepsNewTicketRetryable() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val retryEntered = CountDownLatch(1)
		val retryRelease = CountDownLatch(1)
		val attempts = java.util.concurrent.atomic.AtomicInteger()
		val shared = SessionCoordinator {
			if (attempts.incrementAndGet() == 1) {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
			} else {
				retryEntered.countDown()
				check(retryRelease.await(10, TimeUnit.SECONDS))
			}
		}
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.UNAVAILABLE)
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		main { controller = NativeController { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, cache, { 1L }, emit, SessionStore(context, shared)) }; controller!!.logout() }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			val firstTicket = shared.pendingLogoutTicket()
			lateinit var newer: Any
			main {
				newer = shared.cancelInFlightNow()
				assertTrue(controller!!.state.logoutPending)
				assertTrue("Retry must not be exposed while the controller rejects it", controller!!.state.busy)
			}
			assertNotEquals(firstTicket, newer)
			release.countDown()
			await { shared.pendingLogoutTicket() === newer && controller!!.state.logoutPending && !controller!!.state.busy }
			main { controller!!.logout(); assertTrue(controller!!.state.busy) }
			assertTrue(retryEntered.await(10, TimeUnit.SECONDS))
			main { assertTrue(controller!!.state.logoutPending); assertTrue(controller!!.state.busy) }
			assertEquals(newer, shared.pendingLogoutTicket())
			retryRelease.countDown()
			await { !controller!!.state.logoutPending && !controller!!.state.busy }
			assertNull(shared.pendingLogoutTicket())
		} finally { release.countDown(); retryRelease.countDown(); while (shared.pendingLogoutTicket() != null) shared.logout(shared.pendingLogoutTicket()) }
	}

	@Test fun failedLogoutCanBeRetriedAfterControllerRecreation() {
		var failCleanup = false
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val shared = SessionCoordinator {
			if (failCleanup) throw IllegalStateException("synthetic failure")
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
		}
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.UNAVAILABLE)
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val verifier = IdentityVerifier { _, _ -> SessionVerification.Retry }
		fun create() = NativeController { emit -> SchoolRepository(gateway, verifier, shared, cache, { 1L }, emit, SessionStore(context, shared)) }
		failCleanup = true
		main { controller = create(); controller!!.logout() }
		await { controller!!.state.logoutPending && !controller!!.state.busy }
		val ticket = shared.pendingLogoutTicket()
		assertNotNull(ticket)
		main { controller!!.close(); controller = create(); controller!!.start(); assertTrue(controller!!.state.logoutPending) }
		val password = charArrayOf('x')
		main { controller!!.login("synthetic", password) }
		assertEquals('\u0000', password[0])
		failCleanup = false
		try {
			main { controller!!.logout(); assertTrue(controller!!.state.busy) }
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			main { assertTrue(controller!!.state.busy); assertTrue(controller!!.state.logoutPending) }
			assertEquals(ticket, shared.pendingLogoutTicket())
		} finally { release.countDown() }
		await { !controller!!.state.logoutPending && !controller!!.state.busy }
		assertNull(shared.pendingLogoutTicket())
	}

	@Test fun loginRefreshErrorsLogoutAndStaleDelivery() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		var block = false
		var offline = false
		var blockedLease: RequestLease? = null
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (offline) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				if (block) { blockedLease = lease; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
				return GatewayResult.Success(Diary(context.date, listOf(DiaryDay("Thursday", context.date, listOf(DiarySubject("Synthetic lesson", "lesson", "id", "text", "Synthetic homework", null, false, false))))))
			}
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(listOf(ScheduleDay(0, date, true, false, listOf(ScheduleLesson("lesson", 1, "Synthetic schedule"))))))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":1,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId("1"), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			var value: ByteArray? = null
			override fun read(session: ActiveSession) = value
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean { value = bytes; return true }
		}
		main { controller = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) } }
		val c = controller!!
		val secret = charArrayOf('x')
		main { c.login("synthetic", secret) }
		await { c.state.signedIn && c.state.diary is DiaryState.Ready && c.state.schedule != null }
		assertEquals('\u0000', secret[0])
		main { assertEquals("Synthetic lesson", (c.state.diary as DiaryState.Ready).snapshot.diary.days[0].subjects[0].label); assertEquals("Synthetic schedule", c.state.schedule!!.days[0].lessons[0].themeLabel) }
		val other = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
		try {
			main {
				other.logout()
				val deadline = android.os.SystemClock.uptimeMillis() + 10000
				while (coordinator.pendingLogoutTicket() != null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
				assertNull(coordinator.pendingLogoutTicket())
			}
			main { assertFalse(c.state.signedIn); assertNull(c.state.schedule); assertTrue(c.state.diary is DiaryState.Cleared) }
		} finally { main { other.close() } }
		main { c.login("synthetic", charArrayOf('x')) }
		await { c.state.signedIn && c.state.diary is DiaryState.Ready && c.state.schedule != null }
		offline = true
		main { c.refresh() }
		await { c.state.error == AppError.OFFLINE }
		offline = false; block = true
		main { c.refresh() }
		assertTrue(entered.await(10, TimeUnit.SECONDS))
		main { c.logout(); assertFalse(c.state.signedIn) }
		try {
			assertTrue("Logout must cancel the request before it returns", blockedLease!!.isCancelled())
			await { !c.state.busy }
			main { assertFalse(c.state.signedIn); assertNull(c.state.schedule); assertTrue(c.state.diary is DiaryState.Cleared) }
		} finally { release.countDown() }
	}

	@Test fun logoutCancelsRequestWhileCacheWriteHoldsStorageLock() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val checked = CountDownLatch(1)
		var blockedLease: RequestLease? = null
		var cancelled = false
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				blockedLease = lease
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":1,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId("1"), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				return true
			}
		}
		main { controller = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) } }
		val c = controller!!
		main { c.login("synthetic", charArrayOf('x')) }
		assertTrue(entered.await(10, TimeUnit.SECONDS))
		try {
			main { c.logout(); assertFalse(c.state.signedIn); assertTrue(c.state.busy) }
			val rejectedPassword = charArrayOf('x')
			main { c.login("synthetic", rejectedPassword); assertTrue(c.state.busy) }
			assertEquals('\u0000', rejectedPassword[0])
			val checker = Thread { cancelled = blockedLease!!.isCancelled(); checked.countDown() }
			checker.start()
			assertTrue("Lease cancellation must not wait for disk I/O", checked.await(2, TimeUnit.SECONDS))
			assertTrue(cancelled)
			main { assertTrue("Durable cleanup must remain pending", c.state.busy) }
		} finally { release.countDown() }
		await { !c.state.busy }
		main { assertFalse(c.state.signedIn); assertNull(c.state.schedule); assertTrue(c.state.diary is DiaryState.Cleared) }
	}
}
