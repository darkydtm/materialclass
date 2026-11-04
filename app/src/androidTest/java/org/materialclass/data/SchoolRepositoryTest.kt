package org.materialclass.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.materialclass.api.*
import org.materialclass.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SchoolRepositoryTest {
	private val androidContext = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(androidContext)
	private val store = SessionStore(androidContext, coordinator)
	@After fun cleanup() { coordinator.logout() }

	@Test
	fun loginClearsPasswordWhenRecoveryFails() {
		val journal = object : CleanupJournal {
			override fun read(): CacheNamespace? { throw IllegalStateException("recovery failure") }
			override fun write(namespace: CacheNamespace) { }
			override fun clear() { }
		}
		val failing = SessionCoordinator({}, {}, journal)
		val repository = SchoolRepository(FakeGateway(), IdentityVerifier { _, _ -> SessionVerification.Retry }, failing, FakeCache(), { 1L }, {}, SessionStore(androidContext, failing))
		val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
		try { repository.login("user", password); fail("Recovery must fail") } catch (_: IllegalStateException) { }
		assertTrue(password.all { it == '\u0000' })
	}

	private val context = DiaryContext(1, 2026, 2, "01.01.2026")
	private class FakeGateway : TestSchoolGateway() {
		var identity: String? = "1"
		var role = "student"
		var result: GatewayResult<Diary> = GatewayResult.Success(Diary("01.01.2026", emptyList()))
		var week: GatewayResult<String> = GatewayResult.Failure(AppError.UNAVAILABLE)
		var entered: CountDownLatch? = null
		var release: CountDownLatch? = null
		override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", identity, role, SchoolContext(1, 2026, 2)))
		override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = week
		override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
			entered?.countDown()
			release?.let { check(it.await(10, TimeUnit.SECONDS)) }
			return result
		}
	}
	private class FakeCache : DiaryCache {
		val values = mutableMapOf<AccountId, ByteArray>()
		var accesses = 0
		override fun read(session: ActiveSession): ByteArray? { accesses++; return values[session.account] }
		override fun write(session: ActiveSession, bytes: ByteArray): Boolean { accesses++; values[session.account] = bytes; return true }
	}
	private fun repository(gateway: FakeGateway, cache: FakeCache, events: MutableList<DiaryState>, verify: Boolean = true): SchoolRepository = SchoolRepository(
		gateway, IdentityVerifier { session, _ -> if (verify && session.identityCandidate != null) proof(session) else SessionVerification.Retry }, coordinator, cache, { 1234L }, events::add, store
	)

	private fun proof(session: UnverifiedSession): SessionVerification.Verified {
		val id = session.identityCandidate!!
		val profile = org.json.JSONObject("""{"userId":$id,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
		return SessionVerification.Verified(AccountId(id), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
	}

	@Test
	fun annualDeliveryCannotCrossLogoutOrReplacement() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student", SchoolContext(1, 2026, 2)))
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> {
				if (session.account == "1") { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
				return GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow(session.account, emptyList(), null, null))))
			}
		}
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val deliveries = java.util.Collections.synchronizedList(mutableListOf<AnnualGradesDelivery>())
		val worker = thread { repo.annualGrades { deliveries.add(it) } }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
			assertTrue(repo.login("2", charArrayOf('x')) is GatewayResult.Success)
			repo.annualGrades { deliveries.add(it) }
			release.countDown()
			worker.join(10000)
			assertFalse(worker.isAlive)
			assertEquals(1, deliveries.size)
			val delivery = deliveries.single()
			assertTrue(repo.acceptsAnnualGrades(delivery))
			assertEquals("2", (delivery.result as GatewayResult.Success).value.rows.single().subjectName)
		} finally { release.countDown(); worker.join(10000) }
	}

	@Test
	fun preparedAnnualRequestIsRejectedDuringPendingLogin() {
		val prepared = CountDownLatch(1); val release = CountDownLatch(1)
		val loginEntered = CountDownLatch(1); val releaseLogin = CountDownLatch(1)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				if (login == "2") { loginEntered.countDown(); check(releaseLogin.await(30, TimeUnit.SECONDS)) }
				return GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student", SchoolContext(1, 2026, 2)))
			}
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> {
				calls.incrementAndGet(); return GatewayResult.Success(AnnualGrades(emptyList()))
			}
		}
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.beforeRequestAdmission = { prepared.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
		val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
		val worker = thread { repo.annualGrades { delivered.set(true) } }
		var login: Thread? = null
		try {
			assertTrue(prepared.await(10, TimeUnit.SECONDS))
			login = thread { repo.login("2", charArrayOf('x')) }
			assertTrue(loginEntered.await(10, TimeUnit.SECONDS))
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive)
			assertEquals(0, calls.get()); assertFalse(delivered.get())
		} finally { release.countDown(); releaseLogin.countDown(); worker.join(10000); login?.join(10000) }
	}

	private fun pendingLoginRejectsPreparedRequest(kind: Int) {
		val prepared = CountDownLatch(1); val releaseRequest = CountDownLatch(1)
		val loginEntered = CountDownLatch(1); val releaseLogin = CountDownLatch(1)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				if (login == "new") { loginEntered.countDown(); check(releaseLogin.await(20, TimeUnit.SECONDS)) }
				return GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls.incrementAndGet(); return GatewayResult.Success(Diary(context.date, emptyList())) }
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls.incrementAndGet(); return GatewayResult.Success(context.date) }
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> { calls.incrementAndGet(); return GatewayResult.Success(Schedule(emptyList())) }
		}
		val events = mutableListOf<DiaryState>(); val cache = FakeCache()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repo.login("old", charArrayOf('x')) is GatewayResult.Success)
		repo.beforeRequestAdmission = { prepared.countDown(); check(releaseRequest.await(20, TimeUnit.SECONDS)) }
		var delivered = false
		val worker = thread {
			when (kind) {
				0 -> repo.refresh(context)
				1 -> repo.refreshCurrent()
				2 -> repo.schedule(context.date) { delivered = true }
				else -> repo.currentWeek { delivered = true }
			}
		}
		var login: Thread? = null
		try {
			assertTrue(prepared.await(10, TimeUnit.SECONDS))
			login = thread { repo.login("new", charArrayOf('x')) }
			assertTrue(loginEntered.await(10, TimeUnit.SECONDS))
			val count = events.size
			releaseRequest.countDown(); worker.join(10000)
			assertFalse(worker.isAlive)
			assertEquals(0, calls.get()); assertEquals(count, events.size)
			assertEquals(0, cache.accesses); assertFalse(delivered)
		} finally {
			releaseRequest.countDown(); releaseLogin.countDown()
			worker.join(10000); login?.join(10000)
		}
	}

	private fun cancelledCacheWriteDoesNotPublish(current: Boolean) {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val events = java.util.Collections.synchronizedList(mutableListOf<DiaryState>())
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean {
				entered.countDown()
				check(release.await(20, TimeUnit.SECONDS))
				return true
			}
		}
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repo.login("old", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { if (current) repo.refreshCurrent() else repo.refresh(context) }
		var token: Any? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			token = repo.cancelInFlightNow()
			val count = events.size
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive)
			assertEquals("Cancelled cache write must not publish", count, events.size)
		} finally {
			release.countDown(); worker.join(10000)
			if (token != null) repo.logout(token)
		}
	}

	@Test
	fun loginAndLogoutCallbacksRunOutsideStorageLock() {
		val held = mutableListOf<Boolean>()
		val repo = SchoolRepository(FakeGateway(), IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, { held.add(Thread.holdsLock(org.materialclass.security.StorageLock)) }, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.logout()
		assertEquals(listOf(false, false), held)
	}

	private fun callbackDoesNotHoldStorageLock(schedule: Boolean, expired: Boolean) {
		val entered = CountDownLatch(1); val release = CountDownLatch(1); val loggedOut = CountDownLatch(1)
		val held = java.util.concurrent.atomic.AtomicBoolean(true)
		val applied = java.util.concurrent.atomic.AtomicBoolean(false)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (expired) GatewayResult.Failure(AppError.SESSION_EXPIRED) else GatewayResult.Success(context.date)
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = if (expired) GatewayResult.Failure(AppError.SESSION_EXPIRED) else GatewayResult.Success(Schedule(emptyList()))
		}
		lateinit var repo: SchoolRepository
		val callback = {
			held.set(Thread.holdsLock(org.materialclass.security.StorageLock))
			entered.countDown()
			check(release.await(20, TimeUnit.SECONDS))
		}
		repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, { if (it is DiaryState.Error) callback() }, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val epoch = repo.revocationEpoch()
		val worker = thread {
			if (schedule) repo.schedule(context.date) { callback(); if (repo.revocationEpoch() == epoch) applied.set(true) }
			else repo.currentWeek { callback(); if (repo.revocationEpoch() == epoch) applied.set(true) }
		}
		var logout: Thread? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			logout = thread { repo.logout(); synchronized(org.materialclass.security.StorageLock) { }; loggedOut.countDown() }
			assertTrue("Logout and storage must proceed while the callback is blocked", loggedOut.await(5, TimeUnit.SECONDS))
			assertFalse("Callback must run outside StorageLock", held.get())
		} finally {
			release.countDown(); worker.join(10000); logout?.join(10000)
		}
		assertFalse(worker.isAlive)
		assertFalse("Consumer must reject a result after logout", applied.get())
	}

	private fun loadingCallbackDoesNotBlockLogout(current: Boolean) {
		val entered = CountDownLatch(1); val release = CountDownLatch(1); val loggedOut = CountDownLatch(1)
		val held = java.util.concurrent.atomic.AtomicBoolean(true)
		val applied = java.util.concurrent.atomic.AtomicBoolean(false)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls.incrementAndGet(); return GatewayResult.Success(context.date) }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls.incrementAndGet(); return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		lateinit var repo: SchoolRepository
		var epoch = 0L
		repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (it == DiaryState.Loading) {
				held.set(Thread.holdsLock(org.materialclass.security.StorageLock))
				entered.countDown()
				check(release.await(20, TimeUnit.SECONDS))
				if (repo.revocationEpoch() == epoch) applied.set(true)
			}
		}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		epoch = repo.revocationEpoch()
		val worker = thread { if (current) repo.refreshCurrent() else repo.refresh(context) }
		var logout: Thread? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			logout = thread { repo.logout(); loggedOut.countDown() }
			assertTrue("Logout must proceed while Loading is blocked", loggedOut.await(5, TimeUnit.SECONDS))
			assertFalse("Loading must run outside StorageLock", held.get())
		} finally {
			release.countDown(); worker.join(10000); logout?.join(10000)
		}
		assertFalse(worker.isAlive)
		assertFalse("Consumer must reject stale Loading", applied.get())
		assertEquals(0, calls.get())
	}

	private fun reentrantLoadingLogout(current: Boolean) {
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls.incrementAndGet(); return GatewayResult.Success(context.date) }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls.incrementAndGet(); return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		lateinit var repo: SchoolRepository
		var held = true
		repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (it == DiaryState.Loading) { held = Thread.holdsLock(org.materialclass.security.StorageLock); repo.logout() }
		}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		if (current) repo.refreshCurrent() else repo.refresh(context)
		assertFalse(held)
		assertEquals(0, calls.get())
		assertTrue(coordinator.logoutIfNoActive())
	}

	private fun terminalCallbackDoesNotBlockLogout(current: Boolean, failure: Boolean) {
		val entered = CountDownLatch(1); val release = CountDownLatch(1); val loggedOut = CountDownLatch(1)
		val held = java.util.concurrent.atomic.AtomicBoolean(true)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = if (failure) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (it is DiaryState.Ready || it is DiaryState.Error) {
				held.set(Thread.holdsLock(org.materialclass.security.StorageLock))
				entered.countDown()
				check(release.await(20, TimeUnit.SECONDS))
			}
		}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { if (current) repo.refreshCurrent() else repo.refresh(context) }
		var logout: Thread? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			logout = thread { repo.logout(); loggedOut.countDown() }
			assertTrue("Logout must proceed during a terminal callback", loggedOut.await(5, TimeUnit.SECONDS))
			assertFalse("Terminal callback must run outside StorageLock", held.get())
		} finally {
			release.countDown(); worker.join(10000); logout?.join(10000)
		}
		assertFalse(worker.isAlive)
		assertFalse(logout!!.isAlive)
	}

	private fun queuedTerminalStateIsRejected(current: Boolean, failure: Boolean) {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = if (failure) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		if (current) repo.refreshCurrent() else repo.refresh(context)
		val first = events.last()
		assertTrue(if (failure) first is DiaryState.Error else first is DiaryState.Ready)
		assertTrue(repo.acceptsDiary(first))
		if (current) repo.refreshCurrent() else repo.refresh(context)
		val second = events.last()
		assertFalse(repo.acceptsDiary(first))
		assertTrue(repo.acceptsDiary(second))
		assertTrue(repo.login("2", charArrayOf('x')) is GatewayResult.Success)
		assertFalse(repo.acceptsDiary(second))
		if (current) repo.refreshCurrent() else repo.refresh(context)
		val third = events.last()
		assertTrue(repo.acceptsDiary(third))
		repo.logout()
		assertFalse(repo.acceptsDiary(third))
		assertTrue(repo.acceptsDiary(DiaryState.Cleared))
	}

	private fun reentrantTerminalLogout(current: Boolean, failure: Boolean) {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = if (failure) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success(Diary(context.date, emptyList()))
		}
		lateinit var repo: SchoolRepository
		var called = false
		repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (it is DiaryState.Ready || it is DiaryState.Error) {
				assertFalse(Thread.holdsLock(org.materialclass.security.StorageLock))
				called = true
				repo.logout()
			}
		}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		if (current) repo.refreshCurrent() else repo.refresh(context)
		assertTrue(called)
		assertTrue(coordinator.logoutIfNoActive())
	}

	private fun expiredTerminalStateIsGuarded(current: Boolean) {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (current) GatewayResult.Failure(AppError.SESSION_EXPIRED) else GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.SESSION_EXPIRED)
		}
		val events = mutableListOf<DiaryState>(); val held = mutableListOf<Boolean>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (it is DiaryState.Error) held.add(Thread.holdsLock(org.materialclass.security.StorageLock))
			events.add(it)
		}, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		if (current) repo.refreshCurrent() else repo.refresh(context)
		val expired = events.last() as DiaryState.Error
		assertEquals(AppError.SESSION_EXPIRED, expired.error)
		assertEquals(listOf(false), held)
		assertTrue(repo.acceptsDiary(expired))
		assertTrue(repo.login("2", charArrayOf('x')) is GatewayResult.Success)
		assertFalse(repo.acceptsDiary(expired))
	}

	@Test fun refreshExpiryRejectsNewSession() = expiredTerminalStateIsGuarded(false)
	@Test fun currentRefreshExpiryRejectsNewSession() = expiredTerminalStateIsGuarded(true)

	@Test fun refreshReadyAllowsReentrantLogout() = reentrantTerminalLogout(false, false)
	@Test fun refreshErrorAllowsReentrantLogout() = reentrantTerminalLogout(false, true)
	@Test fun currentRefreshReadyAllowsReentrantLogout() = reentrantTerminalLogout(true, false)
	@Test fun currentRefreshErrorAllowsReentrantLogout() = reentrantTerminalLogout(true, true)

	@Test fun refreshReadyRejectsStaleDelivery() = queuedTerminalStateIsRejected(false, false)
	@Test fun refreshErrorRejectsStaleDelivery() = queuedTerminalStateIsRejected(false, true)
	@Test fun currentRefreshReadyRejectsStaleDelivery() = queuedTerminalStateIsRejected(true, false)
	@Test fun currentRefreshErrorRejectsStaleDelivery() = queuedTerminalStateIsRejected(true, true)

	@Test fun refreshReadyDoesNotBlockLogout() = terminalCallbackDoesNotBlockLogout(false, false)
	@Test fun refreshErrorDoesNotBlockLogout() = terminalCallbackDoesNotBlockLogout(false, true)
	@Test fun currentRefreshReadyDoesNotBlockLogout() = terminalCallbackDoesNotBlockLogout(true, false)
	@Test fun currentRefreshErrorDoesNotBlockLogout() = terminalCallbackDoesNotBlockLogout(true, true)

	@Test fun refreshLoadingDoesNotBlockLogout() = loadingCallbackDoesNotBlockLogout(false)
	@Test fun currentRefreshLoadingDoesNotBlockLogout() = loadingCallbackDoesNotBlockLogout(true)
	@Test fun refreshLoadingCanLogoutReentrantly() = reentrantLoadingLogout(false)
	@Test fun currentRefreshLoadingCanLogoutReentrantly() = reentrantLoadingLogout(true)

	@Test fun scheduleCallbackDoesNotHoldStorageLock() = callbackDoesNotHoldStorageLock(true, false)
	@Test fun weekCallbackDoesNotHoldStorageLock() = callbackDoesNotHoldStorageLock(false, false)
	@Test fun expiredScheduleCallbackDoesNotHoldStorageLock() = callbackDoesNotHoldStorageLock(true, true)
	@Test fun expiredWeekCallbackDoesNotHoldStorageLock() = callbackDoesNotHoldStorageLock(false, true)

	@Test fun cancelledCacheWriteDoesNotPublishRefresh() = cancelledCacheWriteDoesNotPublish(false)
	@Test fun cancelledCacheWriteDoesNotPublishCurrentRefresh() = cancelledCacheWriteDoesNotPublish(true)

	@Test fun pendingLoginRejectsPreparedRefresh() = pendingLoginRejectsPreparedRequest(0)
	@Test fun pendingLoginRejectsPreparedCurrentRefresh() = pendingLoginRejectsPreparedRequest(1)
	@Test fun pendingLoginRejectsPreparedSchedule() = pendingLoginRejectsPreparedRequest(2)
	@Test fun pendingLoginRejectsPreparedCurrentWeek() = pendingLoginRejectsPreparedRequest(3)

	@Test
	fun failedSessionEncodingRevokesActivation() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("x".repeat(70000), "1", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
		}
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
		assertTrue("Failed activation must not remain active", coordinator.logoutIfNoActive())
	}

	@Test
	fun startingLoginSuppressesCancelledDiaryAndScheduleResults() {
		val diaryEntered = CountDownLatch(1); val scheduleEntered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				diaryEntered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
				scheduleEntered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>(); var delivered = false
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val diary = thread { repository.refresh(context) }
		val schedule = thread { repository.schedule(context.date) { delivered = true } }
		check(diaryEntered.await(10, TimeUnit.SECONDS)); check(scheduleEntered.await(10, TimeUnit.SECONDS))
		coordinator.beginLogin()
		val count = events.size
		release.countDown(); diary.join(10000); schedule.join(10000)
		assertFalse(diary.isAlive); assertFalse(schedule.isAlive)
		assertEquals(count, events.size); assertEquals(0, cache.accesses); assertFalse(delivered)
	}

	@Test
	fun offlineCurrentWeekDoesNotUseCacheWithoutKnownDate() {
		var offline = false
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (offline) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success(context.date)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1234L }, events::add, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		repo.refreshCurrent()
		offline = true
		repo.refreshCurrent()
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
	}

	@Test
	fun expiredScheduleRevokesSessionAndHidesCache() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.SESSION_EXPIRED)
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		var delivered = false
		repo.schedule(context.date) { delivered = true }
		assertFalse(delivered)
		assertEquals(AppError.SESSION_EXPIRED, (events.last() as DiaryState.Error).error)
		assertTrue(coordinator.logoutIfNoActive())
	}

	@Test
	fun expiredCurrentWeekRevokesSessionAndHidesCache() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Failure(AppError.SESSION_EXPIRED)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		repo.refreshCurrent()
		assertEquals(AppError.SESSION_EXPIRED, (events.last() as DiaryState.Error).error)
		assertTrue(coordinator.logoutIfNoActive())
	}

	@Test
	fun currentRefreshPublishesOnlyLatestVerifiedWeekAndDiary() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> {
				if (Thread.currentThread().name == "older-week") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return GatewayResult.Success("01.01.2026") }
				return GatewayResult.Success("08.01.2026")
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				calls++; assertEquals(1L, context.schoolId); assertEquals(2026, context.eduYear); assertEquals(2L, context.groupId)
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val events = mutableListOf<DiaryState>(); val cache = FakeCache()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val older = thread(name = "older-week") { repo.refreshCurrent() }
		check(entered.await(10, TimeUnit.SECONDS)); repo.refreshCurrent()
		assertEquals("08.01.2026", (events.last() as DiaryState.Ready).snapshot.diary.date)
		val count = events.size
		release.countDown(); older.join(10000)
		assertFalse(older.isAlive); assertEquals(count, events.size); assertEquals(1, calls); assertEquals(1, cache.accesses)
	}

	@Test
	fun currentRefreshRejectsMissingProofAndUnauthorizedWeek() {
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls++; return GatewayResult.Failure(AppError.UNAVAILABLE) }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { fail("Diary must not be requested"); return GatewayResult.Failure(AppError.UNAVAILABLE) }
		}
		val events = mutableListOf<DiaryState>(); val cache = FakeCache()
		val missing = SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Verified(AccountId("1"), true, null, null) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(missing.login("1", charArrayOf('x')) is GatewayResult.Success)
		missing.refreshCurrent()
		assertEquals(AppError.UNAVAILABLE, (events.last() as DiaryState.Error).error); assertEquals(0, calls)
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.refreshCurrent()
		assertEquals(AppError.UNAVAILABLE, (events.last() as DiaryState.Error).error); assertEquals(1, calls); assertEquals(0, cache.accesses)
	}

	@Test
	fun currentRefreshBeforeAdmissionCannotSubmitAfterLogout() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { calls++; return GatewayResult.Success("01.01.2026") }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.beforeRequestAdmission = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		val worker = thread { repo.refreshCurrent() }
		check(entered.await(10, TimeUnit.SECONDS)); repo.logout(); val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive); assertEquals(0, calls); assertEquals(count, events.size)
	}

	@Test
	fun currentRefreshDoesNotContinueOrPublishAfterLogout() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return GatewayResult.Success("01.01.2026") }
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		val events = mutableListOf<DiaryState>(); val cache = FakeCache()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repo.refreshCurrent() }
		check(entered.await(10, TimeUnit.SECONDS)); repo.logout(); val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive); assertEquals(count, events.size); assertEquals(0, calls); assertEquals(0, cache.accesses)
	}

	@Test
	fun passwordIsClearedBeforeProfileVerification() {
		val password = charArrayOf('x')
		var cleared = false
		val repository = SchoolRepository(FakeGateway(), IdentityVerifier { session, _ -> cleared = password.all { it == '\u0000' }; proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repository.login("1", password) is GatewayResult.Success)
		assertTrue(cleared)
	}

	@Test
	fun logoutBeforeAdmissionPreventsDiarySubmission() {
		val prepared = CountDownLatch(1); val release = CountDownLatch(1)
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		val events = mutableListOf<DiaryState>(); val cache = FakeCache()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		val setter = repository.javaClass.methods.firstOrNull { it.name.startsWith("setBeforeRequestAdmission") && it.parameterCount == 1 }
		assertNotNull("Request admission barrier is required", setter)
		setter!!.invoke(repository, { prepared.countDown(); check(release.await(10, TimeUnit.SECONDS)); Unit })
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.refresh(context) }
		check(prepared.await(10, TimeUnit.SECONDS))
		repository.logout()
		val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(0, calls)
		assertEquals(count, events.size)
		assertEquals(0, cache.accesses)
	}

	@Test
	fun logoutCancelsAdmittedRequestBeforeConnectionRegistration() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		var registered = true
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				registered = lease.register { }
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.refresh(context) }
		check(entered.await(10, TimeUnit.SECONDS)); repository.logout(); val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive); assertFalse(registered); assertEquals(count, events.size); assertEquals(0, cache.accesses)
	}

	@Test
	fun logoutDisconnectsInFlightRequestWithoutPublishing() {
		val entered = CountDownLatch(1); val disconnected = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				check(lease.register { disconnected.countDown() }); entered.countDown()
				check(release.await(10, TimeUnit.SECONDS)); lease.unregister()
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.refresh(context) }
		check(entered.await(10, TimeUnit.SECONDS)); repository.logout(); val count = events.size
		check(disconnected.await(10, TimeUnit.SECONDS)); release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive); assertEquals(count, events.size); assertEquals(0, cache.accesses)
	}

	@Test
	fun logoutBeforeAdmissionPreventsScheduleSubmission() {
		val prepared = CountDownLatch(1); val release = CountDownLatch(1)
		var calls = 0; var delivered = false
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> { calls++; return GatewayResult.Success(Schedule(emptyList())) }
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		repository.beforeRequestAdmission = { prepared.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.schedule("01.01.2026") { delivered = true } }
		check(prepared.await(10, TimeUnit.SECONDS)); repository.logout()
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive); assertEquals(0, calls); assertFalse(delivered)
	}

	@Test
	fun unverifiedDiaryContextCannotReachGateway() {
		var calls = 0
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> { calls++; return GatewayResult.Success(Diary(context.date, emptyList())) }
		}
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		listOf(DiaryContext(9, 2026, 2, context.date), DiaryContext(1, 2026, 9, context.date), DiaryContext(1, 2027, 2, context.date)).forEach {
			repository.refresh(it)
			assertTrue(events.last() is DiaryState.Error)
		}
		assertEquals(0, calls)
	}

	@Test
	fun rejectedContextSupersedesInFlightValidRefresh() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = FakeGateway().apply { this.entered = entered; this.release = release }
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repo = repository(gateway, cache, events)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repo.refresh(context) }
		check(entered.await(10, TimeUnit.SECONDS))
		repo.refresh(DiaryContext(9, 2026, 2, context.date))
		assertTrue(events.last() is DiaryState.Error)
		val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(count, events.size)
		assertEquals(0, cache.accesses)
	}

	@Test
	fun missingScheduleProofDeliversUnavailableOnce() {
		val events = mutableListOf<DiaryState>()
		val repository = repository(FakeGateway(), FakeCache(), events)
		assertTrue(repository.login("a", charArrayOf('x')) is GatewayResult.Success)
		val results = mutableListOf<GatewayResult<Schedule>>()
		repository.schedule("01.01.2026", results::add)
		assertEquals(1, results.size)
		assertEquals(AppError.UNAVAILABLE, (results.single() as GatewayResult.Failure).error)
	}

	@Test
	fun scheduleCannotPublishAcrossAccountSwitch() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		var result: GatewayResult<Schedule>? = null
		repository.schedule("01.01.2026") { result = it }
		assertNull(result)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.schedule("01.01.2026") { result = it } }
		check(entered.await(10, TimeUnit.SECONDS))
		assertTrue(repository.login("2", charArrayOf('x')) is GatewayResult.Success)
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertNull(result)
		repository.logout()
	}

	@Test
	fun scheduleDoesNotDeliverAfterLogout() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		var delivered = false
		val worker = thread { repository.schedule("01.01.2026") { delivered = true } }
		check(entered.await(10, TimeUnit.SECONDS))
		repository.logout()
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertFalse(delivered)
	}

	@Test
	fun olderScheduleCannotReturnAfterNewerRequest() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
				if (date == "01.01.2026") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {}, store)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		var older: GatewayResult<Schedule>? = null
		var newer: GatewayResult<Schedule>? = null
		val worker = thread { repository.schedule("01.01.2026") { older = it } }
		check(entered.await(10, TimeUnit.SECONDS))
		repository.schedule("02.01.2026") { newer = it }
		assertTrue(newer is GatewayResult.Success)
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertNull(older)
		repository.logout()
	}

	@Test
	fun olderLoginCannotPublishAfterNewerLoginStarts() {
		val enteredOld = CountDownLatch(1); val entered = CountDownLatch(2); val releaseOld = CountDownLatch(1); val releaseNew = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				entered.countDown()
				if (login == "older") enteredOld.countDown()
				check((if (login == "older") releaseOld else releaseNew).await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(UnverifiedSession("synthetic-token", if (login == "older") "1" else "2", "student", SchoolContext(1, 2026, 2)))
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		var older: GatewayResult<Unit>? = null; var newer: GatewayResult<Unit>? = null
		val first = thread { older = repository.login("older", charArrayOf('x')) }
		check(enteredOld.await(10, TimeUnit.SECONDS))
		val second = thread { newer = repository.login("newer", charArrayOf('x')) }
		check(entered.await(10, TimeUnit.SECONDS))
		releaseOld.countDown(); first.join(10000)
		assertFalse(first.isAlive)
		assertTrue(older is GatewayResult.Failure)
		releaseNew.countDown(); second.join(10000)
		assertFalse(second.isAlive)
		assertTrue(newer is GatewayResult.Success)
		repository.refresh(context)
		assertEquals(setOf(AccountId("2")), cache.values.keys)
		repository.logout()
	}

	@Test
	fun throwingSwitchCallbackRevokesPriorSessionBeforeLogout() {
		val gateway = FakeGateway()
		var throwOnClear = false
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (throwOnClear && it == DiaryState.Cleared) throw IllegalStateException("synthetic callback failure")
		}, store)
		assertTrue(repo.login("old", charArrayOf('x')) is GatewayResult.Success)
		val sessionFile = java.io.File(androidContext.noBackupFilesDir, "session")
		assertTrue(sessionFile.exists())
		gateway.identity = "2"
		throwOnClear = true
		assertTrue(repo.login("new", charArrayOf('x')) is GatewayResult.Failure)
		throwOnClear = false
		repo.logout()
		assertTrue("Prior session must be durably removed", !sessionFile.exists())
		assertTrue("Prior session must not remain active", coordinator.logoutIfNoActive())
		assertFalse(repo.restore() is RestoreResult.Restored)
	}

	@Test
	fun failedSwitchStorageRevocationPrecedesCallback() {
		var failClear = false
		var callbacks = 0
		val failingCoordinator = SessionCoordinator {
			if (failClear) throw java.io.IOException("synthetic failure")
			SessionStore.clear(androidContext)
		}
		val failingStore = SessionStore(androidContext, failingCoordinator)
		val gateway = FakeGateway()
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, failingCoordinator, FakeCache(), { 1L }, { callbacks++ }, failingStore)
		assertTrue(repo.login("old", charArrayOf('x')) is GatewayResult.Success)
		gateway.identity = "2"
		failClear = true
		assertTrue(repo.login("new", charArrayOf('x')) is GatewayResult.Failure)
		assertEquals(1, callbacks)
		assertFalse(repo.restore() is RestoreResult.Restored)
		failClear = false
		repo.logout()
		assertFalse(java.io.File(androidContext.noBackupFilesDir, "session").exists())
	}

	@Test
	fun reentrantNewLoginSurvivesThrowingOlderCallback() {
		val gateway = FakeGateway()
		val newer = repository(gateway, FakeCache(), mutableListOf())
		var switch = false
		val older = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, {
			if (switch && it == DiaryState.Cleared) {
				gateway.identity = "3"
				assertTrue(newer.login("newer", charArrayOf('x')) is GatewayResult.Success)
				throw IllegalStateException("synthetic callback failure")
			}
		}, store)
		assertTrue(older.login("old", charArrayOf('x')) is GatewayResult.Success)
		gateway.identity = "2"
		switch = true
		assertTrue(older.login("switch", charArrayOf('x')) is GatewayResult.Failure)
		switch = false
		older.logout()
		var delivered = false
		newer.refresh(context)
		newer.currentWeek { delivered = true }
		assertTrue(delivered)
		assertFalse(coordinator.logoutIfNoActive())
	}

	@Test
	fun switchingAccountsClearsPreviouslyEmittedDiary() {
		val gateway = FakeGateway(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, FakeCache(), events)
		repository.login("a", charArrayOf('x'))
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		gateway.identity = "2"
		assertTrue(repository.login("b", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(DiaryState.Cleared, events.last())
		repository.logout()
	}

	@Test
	fun logoutClearsPreviouslyEmittedDiary() {
		val gateway = FakeGateway(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, FakeCache(), events)
		repository.login("synthetic", charArrayOf('x'))
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		repository.logout()
		assertFalse(events.last() is DiaryState.Ready)
	}

	@Test
	fun logoutClearsDiaryWhenStorageRevocationFails() {
		var failClear = false
		val failingCoordinator = SessionCoordinator { if (failClear) throw java.io.IOException("synthetic failure") }
		val failingStore = SessionStore(androidContext, failingCoordinator)
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(FakeGateway(), IdentityVerifier { session, _ -> proof(session) }, failingCoordinator, FakeCache(), { 1L }, events::add, failingStore)
		assertTrue(repository.login("a", charArrayOf('x')) is GatewayResult.Success)
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		failClear = true
		try {
			repository.logout()
			fail("Storage failure must propagate")
		} catch (_: java.io.IOException) {
			assertEquals(DiaryState.Cleared, events.last())
		} finally {
			failClear = false
			failingCoordinator.pendingLogoutTicket()?.let { failingCoordinator.logout(it) }
		}
	}

	@Test
	fun logoutOfStaleRepositoryDoesNotRevokeNewerLogin() {
		val gateway = FakeGateway(); val olderEvents = mutableListOf<DiaryState>(); val newerEvents = mutableListOf<DiaryState>()
		val older = repository(gateway, FakeCache(), olderEvents)
		assertTrue(older.login("a", charArrayOf('x')) is GatewayResult.Success)
		older.refresh(context)
		gateway.identity = "2"
		val newer = repository(gateway, FakeCache(), newerEvents)
		assertTrue(newer.login("b", charArrayOf('x')) is GatewayResult.Success)
		older.logout()
		assertEquals(DiaryState.Cleared, olderEvents.last())
		newer.refresh(context)
		assertTrue(newerEvents.last() is DiaryState.Ready)
	}

	@Test
	fun cancellationIsNotConvertedToUnavailable() {
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = throw java.util.concurrent.CancellationException()
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> SessionVerification.Retry }, coordinator, FakeCache(), { 0L }, {}, store)
		val password = charArrayOf('x')
		try {
			repository.login("synthetic", password)
			fail("Cancellation must propagate")
		} catch (_: java.util.concurrent.CancellationException) {
			assertTrue(password.all { it == '\u0000' })
		}
	}

	@Test
	fun verifiedLoginCachesEmptySuccessAndOfflinePreservesTimestamp() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, cache, events)
		val password = "synthetic".toCharArray()
		assertTrue(repository.login("not-an-identity", password) is GatewayResult.Success)
		assertTrue(password.all { it == '\u0000' })
		repository.refresh(context)
		val fresh = events.last() as DiaryState.Ready
		assertTrue(fresh.snapshot.diary.days.isEmpty())
		assertNull(fresh.staleError)
		assertEquals(1234L, fresh.snapshot.lastSuccessMillis)
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repository.refresh(context)
		val stale = events.last() as DiaryState.Ready
		assertEquals(AppError.OFFLINE, stale.staleError)
		assertEquals(1234L, stale.snapshot.lastSuccessMillis)
		gateway.result = GatewayResult.Success(Diary("08.01.2026", emptyList()))
		repository.refresh(context)
		assertEquals(AppError.INCOMPATIBLE_RESPONSE, (events.last() as DiaryState.Error).error)
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repository.refresh(DiaryContext(context.schoolId, context.eduYear, context.groupId, "08.01.2026"))
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
		repository.logout()
		val count = events.size
		repository.refresh(context)
		assertEquals(count, events.size)
	}

	@Test
	fun diaryFallbackOnlyForConnectivityFailuresOnBothRefreshPaths() {
		for (current in listOf(false, true)) {
			val gateway = FakeGateway().apply { week = GatewayResult.Success(context.date) }
			val cache = FakeCache(); val events = mutableListOf<DiaryState>()
			val repo = repository(gateway, cache, events)
			assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
			fun refresh() { if (current) repo.refreshCurrent() else repo.refreshDate(context.date) }
			refresh()
			val fresh = (events.last() as DiaryState.Ready).snapshot
			for (error in AppError.values().filter { it != AppError.SESSION_EXPIRED }) {
				gateway.result = GatewayResult.Failure(error)
				val before = cache.accesses
				refresh()
				if (error == AppError.OFFLINE || error == AppError.TIMEOUT) {
					val stale = events.last() as DiaryState.Ready
					assertEquals(fresh.lastSuccessMillis, stale.snapshot.lastSuccessMillis)
					assertEquals(fresh.diary.date, stale.snapshot.diary.date)
					assertTrue(stale.snapshot.diary.days.isEmpty())
					assertEquals(error, stale.staleError)
					assertEquals(before + 1, cache.accesses)
				} else {
					assertEquals(error, (events.last() as DiaryState.Error).error)
					assertEquals(before, cache.accesses)
				}
			}
			gateway.result = GatewayResult.Success(Diary("08.01.2026", emptyList()))
			val before = cache.accesses
			refresh()
			assertEquals(AppError.INCOMPATIBLE_RESPONSE, (events.last() as DiaryState.Error).error)
			assertEquals(before, cache.accesses)
			repo.logout()
		}
	}

	@Test
	fun unreadableDiaryCacheIsReportedOnlyForConnectivityFailures() {
		for (current in listOf(false, true)) {
			val gateway = FakeGateway().apply { week = GatewayResult.Success(context.date) }
			var reads = 0
			val cache = object : DiaryCache {
				override fun read(session: ActiveSession): ByteArray? { reads++; throw java.io.IOException() }
				override fun write(session: ActiveSession, bytes: ByteArray) = true
			}
			val events = mutableListOf<DiaryState>()
			val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
			assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
			for (error in listOf(AppError.UNAVAILABLE, AppError.FORBIDDEN, AppError.OFFLINE, AppError.TIMEOUT)) {
				gateway.result = GatewayResult.Failure(error)
				if (current) repo.refreshCurrent() else repo.refreshDate(context.date)
				val expected = if (error == AppError.OFFLINE || error == AppError.TIMEOUT) AppError.CACHE_UNREADABLE else error
				assertEquals(expected, (events.last() as DiaryState.Error).error)
			}
			assertEquals(2, reads)
			repo.logout()
		}
	}

	@Test
	fun currentWeekFailuresNeverReadDiaryCache() {
		val gateway = FakeGateway().apply { week = GatewayResult.Success(context.date) }
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repo = repository(gateway, cache, events)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.refreshCurrent()
		for (error in AppError.values().filter { it != AppError.SESSION_EXPIRED }) {
			gateway.week = GatewayResult.Failure(error)
			val before = cache.accesses
			repo.refreshCurrent()
			assertEquals(error, (events.last() as DiaryState.Error).error)
			assertEquals(before, cache.accesses)
		}
		repo.logout()
	}

	@Test
	fun corruptDiaryCacheIsUnreadableOnOfflineRefresh() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repo = repository(gateway, cache, events)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.refresh(context)
		cache.values[AccountId("1")] = byteArrayOf(1)
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repo.refreshDate(context.date)
		assertEquals(AppError.CACHE_UNREADABLE, (events.last() as DiaryState.Error).error)
		repo.logout()
	}

	@Test
	fun expiredDiaryRevokesSessionWithoutReadingCachedData() {
		for (current in listOf(false, true)) {
			val gateway = FakeGateway().apply { week = GatewayResult.Success(context.date) }
			val cache = FakeCache(); val events = mutableListOf<DiaryState>()
			val repo = repository(gateway, cache, events)
			assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
			repo.refresh(context)
			val before = cache.accesses
			gateway.result = GatewayResult.Failure(AppError.SESSION_EXPIRED)
			if (current) repo.refreshCurrent() else repo.refreshDate(context.date)
			assertEquals(AppError.SESSION_EXPIRED, (events.last() as DiaryState.Error).error)
			assertEquals(before, cache.accesses)
			assertTrue(coordinator.logoutIfNoActive())
		}
	}

	@Test
	fun cancelledOfflineDiaryCannotReadOrPublishCache() {
		for (current in listOf(false, true)) {
			val gateway = FakeGateway().apply { week = GatewayResult.Success(context.date) }
			val cache = FakeCache(); val events = java.util.Collections.synchronizedList(mutableListOf<DiaryState>())
			val repo = repository(gateway, cache, events)
			assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
			repo.refresh(context)
			gateway.result = GatewayResult.Failure(AppError.OFFLINE)
			gateway.entered = CountDownLatch(1); gateway.release = CountDownLatch(1)
			val worker = thread { if (current) repo.refreshCurrent() else repo.refreshDate(context.date) }
			var token: Any? = null
			try {
				assertTrue(gateway.entered!!.await(10, TimeUnit.SECONDS))
				token = repo.cancelInFlightNow()
				val count = events.size; val before = cache.accesses
				gateway.release!!.countDown(); worker.join(10000)
				assertFalse(worker.isAlive); assertEquals(count, events.size); assertEquals(before, cache.accesses)
			} finally {
				gateway.release!!.countDown(); worker.join(10000)
				if (token != null) repo.logout(token)
			}
		}
	}

	@Test
	fun offlineDiaryCacheDoesNotCrossAccountsOrDates() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repo = repository(gateway, cache, events)
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Success)
		repo.refresh(context)
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repo.refreshDate("08.01.2026")
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
		gateway.identity = "2"
		assertTrue(repo.login("2", charArrayOf('x')) is GatewayResult.Success)
		repo.refresh(context)
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
		repo.logout()
	}

	@Test
	fun unverifiedOrNonStudentIdentityNeverOpensCache() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, cache, events, false)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
		repository.refresh(context)
		gateway.role = "teacher"
		val other = repository(gateway, cache, events)
		assertTrue(other.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
		other.refresh(context)
		assertEquals(0, cache.accesses)
	}

	@Test
	fun selectedWeekCacheIsRestrictedToMatchingDate() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, cache, events)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		gateway.result = GatewayResult.Success(Diary("08.01.2026", emptyList()))
		repository.refreshDate("08.01.2026")
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repository.refreshDate("08.01.2026")
		val saved = events.last() as DiaryState.Ready
		assertEquals("08.01.2026", saved.snapshot.diary.date)
		assertEquals(AppError.OFFLINE, saved.staleError)
		repository.refreshDate("01.01.2026")
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
	}

	@Test
	fun delayedSelectedWeekCannotPublishAfterAccountSwitch() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", if (login == "old") "1" else "2", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				if (session.remote.identityCandidate == "1") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val events = java.util.Collections.synchronizedList(mutableListOf<DiaryState>())
		val cache = FakeCache()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repository.login("old", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.refreshDate("08.01.2026") }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertTrue(repository.login("new", charArrayOf('x')) is GatewayResult.Success)
			repository.refreshDate("01.01.2026")
			val count = events.size
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive)
			assertEquals(count, events.size)
			assertEquals("01.01.2026", (events.last() as DiaryState.Ready).snapshot.diary.date)
			assertFalse(cache.values.containsKey(AccountId("1")))
			assertTrue(cache.values.containsKey(AccountId("2")))
		} finally {
			release.countDown(); worker.join(10000)
		}
	}

	@Test
	fun olderRefreshCannotOverwriteNewerRefresh() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				if (Thread.currentThread().name == "older") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
					return GatewayResult.Failure(AppError.OFFLINE)
				}
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1234L }, events::add, store)
		repository.login("synthetic", charArrayOf('x'))
		val worker = thread(name = "older") { repository.refresh(context) }
		check(entered.await(10, TimeUnit.SECONDS))
		repository.refresh(context)
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertNull((events.last() as DiaryState.Ready).staleError)
		repository.logout()
	}

	@Test
	fun offlineDoesNotReadAnotherSchoolContext() {
		val gateway = FakeGateway(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, FakeCache(), events)
		repository.login("synthetic", charArrayOf('x'))
		repository.refresh(context)
		gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		repository.refresh(DiaryContext(9, 2026, 2, context.date))
		assertTrue(events.last() is DiaryState.Error)
	}

	@Test
	fun offlineWithoutCacheIsErrorNotEmptySuccess() {
		val gateway = FakeGateway(); gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		val events = mutableListOf<DiaryState>(); val repository = repository(gateway, FakeCache(), events)
		repository.login("synthetic", charArrayOf('x'))
		repository.refresh(context)
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
		repository.logout()
	}

	@Test
	fun expiredOldSessionDoesNotCancelPendingNewLogin() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				if (login == "newer") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
				}
				return GatewayResult.Success(UnverifiedSession("synthetic-token", if (login == "older") "1" else "2", "student", SchoolContext(1, 2026, 2)))
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.SESSION_EXPIRED)
		}
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, FakeCache(), { 1L }, events::add, store)
		assertTrue(repository.login("older", charArrayOf('x')) is GatewayResult.Success)
		var newer: GatewayResult<Unit>? = null
		val worker = thread { newer = repository.login("newer", charArrayOf('x')) }
		check(entered.await(10, TimeUnit.SECONDS))
		repository.refresh(context)
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertTrue(newer is GatewayResult.Success)
		repository.logout()
	}

	@Test
	fun expiredResponseFromOldSessionCannotRevokeNewLogin() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", if (login == "older") "1" else "2", "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
				if (session.remote.identityCandidate == "1") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
					return GatewayResult.Failure(AppError.SESSION_EXPIRED)
				}
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		assertTrue(repository.login("older", charArrayOf('x')) is GatewayResult.Success)
		val worker = thread { repository.refresh(context) }
		check(entered.await(10, TimeUnit.SECONDS))
		assertTrue(repository.login("newer", charArrayOf('x')) is GatewayResult.Success)
		val count = events.size
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(count, events.size)
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		assertEquals(setOf(AccountId("2")), cache.values.keys)
		repository.logout()
	}

	@Test
	fun delayedResponseCannotWriteOrEmitAfterAccountSwitch() {
		val gateway = FakeGateway(); val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val first = repository(gateway, cache, events)
		first.login("a", charArrayOf('x'))
		gateway.entered = CountDownLatch(1); gateway.release = CountDownLatch(1)
		val worker = thread { first.refresh(context) }
		check(gateway.entered!!.await(10, TimeUnit.SECONDS))
		first.logout()
		gateway.identity = "2"
		val second = repository(gateway, cache, events)
		second.login("b", charArrayOf('x'))
		val count = events.size
		gateway.release!!.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(count, events.size)
		assertEquals(0, cache.accesses)
		second.logout()
	}
}
