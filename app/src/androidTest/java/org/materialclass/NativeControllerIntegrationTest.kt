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
	@Test fun gradeFileDeletionFailureKeepsLogoutRetryable() = gradeDeletionFailure(true)

	@Test fun gradeKeyDeletionFailureKeepsLogoutRetryable() = gradeDeletionFailure(false)

	private fun gradeDeletionFailure(failFile: Boolean) {
		val keys = org.materialclass.security.KeystoreKeys()
		val journal = FileCleanupJournal(context)
		val failing = java.util.concurrent.atomic.AtomicBoolean(false)
		val hits = java.util.concurrent.atomic.AtomicInteger()
		var gradeId = ""
		val shared = SessionCoordinator(
			{ SessionStore.clear(context) },
			{ namespace -> org.materialclass.cache.EncryptedCache.clearNamespace(context, namespace,
				{ file -> if (failing.get() && failFile && file.name == gradeId) { hits.incrementAndGet(); false } else file.delete() },
				{ alias -> if (failing.get() && !failFile && alias == "cache-$gradeId") { hits.incrementAndGet(); throw java.io.IOException("Synthetic key deletion failure") } else keys.delete(alias) },
			) },
			journal,
			{ keys.delete("session") },
		)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Failure(AppError.UNAVAILABLE)
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.UNAVAILABLE)
		}
		val cache = org.materialclass.cache.EncryptedCache(context, shared, "grades")
		val otherNamespace = CacheNamespace.create()
		val files = org.materialclass.cache.CacheFiles(context)
		var otherId = ""
		try {
			val session = shared.activate(AccountId("synthetic-grade-cleanup"))
			val payload = byteArrayOf(10, 20, 30)
			assertTrue(cache.write(session, payload))
			assertArrayEquals(payload, cache.read(session))
			gradeId = keys.namespace(session.cacheNamespace.value + ":grades", false)
			val gradeFile = java.io.File(files.directory, gradeId)
			assertTrue(gradeFile.isFile)
			assertTrue(keys.contains("cache-$gradeId"))
			otherId = keys.namespace(otherNamespace.value + ":grades", false)
			val other = org.materialclass.security.EncryptedFile(java.io.File(files.directory, otherId), "cache-$otherId", keys, files)
			other.write(byteArrayOf(42))
			main { controller = NativeController { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, EncryptedDiaryCache(cache), { 1L }, emit, SessionStore(context, shared)) } }
			failing.set(true)
			main { controller!!.logout() }
			await { controller!!.state.logoutPending && !controller!!.state.busy && controller!!.state.error == AppError.UNAVAILABLE }
			assertEquals(1, hits.get())
			val ticket = shared.pendingLogoutTicket()
			assertNotNull(ticket)
			assertEquals(session.cacheNamespace.value, journal.read()?.value)
			assertNull(shared.admit(session))
			assertEquals(failFile, gradeFile.exists())
			assertEquals(!failFile, keys.contains("cache-$gradeId"))
			assertArrayEquals(byteArrayOf(42), other.read())
			failing.set(false)
			main { controller!!.logout() }
			await { !controller!!.state.logoutPending && !controller!!.state.busy }
			assertNull(shared.pendingLogoutTicket())
			assertNull(journal.read())
			assertFalse(gradeFile.exists())
			assertFalse(keys.contains("cache-$gradeId"))
			assertArrayEquals(byteArrayOf(42), other.read())
			main { assertNull(controller!!.state.error); assertFalse(controller!!.state.signedIn) }
		} finally {
			failing.set(false)
			main { controller?.close(); controller = null }
			shared.pendingLogoutTicket()?.let { shared.logout(it) }
			if (otherId.isNotEmpty()) org.materialclass.cache.EncryptedCache.clearNamespace(context, otherNamespace)
			shared.logout()
			cache.clearAll()
		}
	}

	@Test fun logoutCompletionWaitsForUiRevocation() {
		val cleaned = CountDownLatch(1)
		val shared = SessionCoordinator { cleaned.countDown() }
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
		main { controller = NativeController { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, cache, { 1L }, emit, SessionStore(context, shared)) } }
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val completed = CountDownLatch(1)
		val cancelled = CountDownLatch(1)
		android.os.Handler(android.os.Looper.getMainLooper()).post { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
		var worker: Thread? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			worker = kotlin.concurrent.thread {
				val token = shared.cancelInFlightNow()
				cancelled.countDown()
				shared.logout(token)
				completed.countDown()
			}
			assertTrue(cancelled.await(10, TimeUnit.SECONDS))
			assertTrue("Durable cleanup did not complete", cleaned.await(10, TimeUnit.SECONDS))
			synchronized(org.materialclass.security.StorageLock) { }
			assertNotNull("Admission must remain closed until UI revocation", shared.pendingLogoutTicket())
			assertFalse("Logout completed before the UI processed revocation", completed.await(2, TimeUnit.SECONDS))
		} finally {
			release.countDown()
			worker?.join(10000)
		}
		assertFalse(worker!!.isAlive)
		assertTrue(completed.await(1, TimeUnit.SECONDS))
	}
	@Test fun privateDataAndLoginStayGatedUntilBothControllersAcknowledge() {
		val cleaned = CountDownLatch(2)
		val shared = SessionCoordinator { cleaned.countDown() }
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
		val held = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
		val secondDelivered = CountDownLatch(1)
		lateinit var secondEmit: (DiaryState) -> Unit
		fun create(secondController: Boolean = false) = NativeController(
			uiDispatch = { action ->
				if (secondController) { held.set(action); secondDelivered.countDown(); true }
				else android.os.Handler(android.os.Looper.getMainLooper()).post(action)
			},
		) { emit ->
			if (secondController) secondEmit = emit
			SchoolRepository(gateway, verifier, shared, cache, { 1L }, emit, SessionStore(context, shared))
		}
		lateinit var second: NativeController
		main { controller = create(); second = create(true); controller!!.login("1", charArrayOf('x')) }
		await { controller!!.state.signedIn && controller!!.state.diary is DiaryState.Ready }
		main {
			val generation = NativeController::class.java.getDeclaredField("generation").apply { isAccessible = true }.getLong(second)
			NativeController::class.java.getDeclaredField("currentTaskGeneration").apply { isAccessible = true }.setLong(second, generation)
			NativeController::class.java.getDeclaredField("currentTaskEpoch").apply { isAccessible = true }.setLong(second, shared.revocationEpoch())
			secondEmit(DiaryState.Ready(DiarySnapshot(Diary("01.01.2026", emptyList()), 1L)))
		}
		await { second.state.diary is DiaryState.Ready }
		val cancelled = CountDownLatch(1)
		val completed = CountDownLatch(1)
		val worker = kotlin.concurrent.thread {
			val ticket = shared.cancelInFlightNow()
			cancelled.countDown()
			shared.logout(ticket)
			completed.countDown()
		}
		try {
			assertTrue(cancelled.await(10, TimeUnit.SECONDS))
			assertTrue(secondDelivered.await(10, TimeUnit.SECONDS))
			assertTrue(cleaned.await(10, TimeUnit.SECONDS))
			await { controller!!.state.diary is DiaryState.Cleared }
			assertNotNull(shared.pendingLogoutTicket())
			assertNull(shared.admit(shared.beginLogin()))
			main { held.get()!!.invoke(); assertTrue(second.state.diary is DiaryState.Cleared) }
			assertTrue(completed.await(10, TimeUnit.SECONDS))
			await { shared.pendingLogoutTicket() == null && !second.state.logoutPending }
			main { second.login("2", charArrayOf('x')) }
			await { second.state.signedIn && second.state.diary is DiaryState.Ready }
		} finally {
			main { held.get()?.invoke(); second.close() }
			worker.join(10000)
		}
	}

	@Test fun expiryAndUnticketedLogoutKeepAdmissionClosedUntilUiAcknowledges() {
		for (expiry in listOf(true, false)) {
			val shared = SessionCoordinator { }
			val session = shared.activate(AccountId("synthetic-old"))
			val delivered = CountDownLatch(1)
			val finished = CountDownLatch(1)
			var acknowledge: (() -> Unit)? = null
			val observer: (Long, () -> Unit) -> Unit = { _, ack ->
				assertFalse(Thread.holdsLock(org.materialclass.security.StorageLock))
				acknowledge = ack
				delivered.countDown()
			}
			shared.observeUiRevocations(observer)
			val worker = kotlin.concurrent.thread {
				try {
					if (expiry) shared.revokeIfActive(session, expired = true) else shared.logout()
				} finally { finished.countDown() }
			}
			try {
				assertTrue("UI revocation was not delivered", delivered.await(10, TimeUnit.SECONDS))
				assertNotNull("Cleanup must not release admission before UI clear", shared.pendingLogoutTicket())
				assertNull(shared.admit(shared.beginLogin()))
				if (!expiry) assertFalse("Unticketed logout returned before UI acknowledgement", finished.await(1, TimeUnit.SECONDS))
			} finally {
				acknowledge?.invoke()
				shared.removeUiRevocationObserver(observer)
				worker.join(10000)
				shared.pendingLogoutTicket()?.let { shared.logout(it) }
			}
			assertTrue(finished.await(10, TimeUnit.SECONDS))
			assertNull(shared.pendingLogoutTicket())
		}
	}

	@Test fun repositoryUnticketedLogoutWaitsForSelectedUiAcknowledgement() {
		val shared = SessionCoordinator { }
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
		val repository = SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, cache, { 1L }, {}, SessionStore(context, shared))
		val delivered = CountDownLatch(1)
		val finished = CountDownLatch(1)
		val held = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
		val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
		val observer: (Long, () -> Unit) -> Unit = { _, ack -> held.set(ack); delivered.countDown() }
		shared.observeUiRevocations(observer)
		val worker = kotlin.concurrent.thread {
			try { repository.logout() } catch (error: Throwable) { failure.set(error) } finally { finished.countDown() }
		}
		try {
			assertTrue(delivered.await(10, TimeUnit.SECONDS))
			val ticket = shared.pendingLogoutTicket()
			assertNotNull(ticket)
			assertNull(shared.admit(shared.beginLogin()))
			assertFalse("Repository returned before UI acknowledgement", finished.await(1, TimeUnit.SECONDS))
			held.get()!!.invoke()
			assertTrue(finished.await(10, TimeUnit.SECONDS))
			assertNull(failure.get())
			assertNull(shared.pendingLogoutTicket())
			val newer = shared.activate(AccountId("synthetic-new"))
			shared.logout(ticket)
			assertNotNull(shared.admit(newer))
		} finally {
			held.get()?.invoke()
			shared.removeUiRevocationObserver(observer)
			worker.join(10000)
			shared.pendingLogoutTicket()?.let { shared.logout(it) }
			shared.logout()
		}
	}

	@Test fun failedUiDeliveryReportsFailureAndKeepsTicketPending() {
		val shared = SessionCoordinator { }
		val observer: (Long, () -> Unit) -> Unit = { _, _ -> throw IllegalStateException("Synthetic delivery failure") }
		shared.observeUiRevocations(observer)
		val ticket = shared.cancelInFlightNow()
		try {
			try {
				shared.logout(ticket)
				fail("Failed UI delivery must report cleanup failure")
			} catch (_: IllegalStateException) { }
			assertSame(ticket, shared.pendingLogoutTicket())
			assertNull(shared.admit(shared.beginLogin()))
		} finally {
			shared.removeUiRevocationObserver(observer)
			shared.pendingLogoutTicket()?.let { shared.logout(it) }
		}
	}

	@Test fun controllerRetriesFailedDispatchAndFailedApply() {
		for (failDispatch in listOf(true, false)) {
			val shared = SessionCoordinator { }
			val failing = java.util.concurrent.atomic.AtomicBoolean(true)
			val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
			fun create() = NativeController(
				uiDispatch = { action -> if (failDispatch && failing.get()) false else handler.post(action) },
				applyUiRevocation = { action -> if (!failDispatch && failing.get()) throw IllegalStateException("Synthetic apply failure") else action() },
			) { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, shared, cache, { 1L }, emit, SessionStore(context, shared)) }
			try {
				main { controller = create(); controller!!.logout() }
				await { controller!!.state.logoutPending && !controller!!.state.busy && controller!!.state.error == AppError.UNAVAILABLE }
				val ticket = shared.pendingLogoutTicket()
				assertNotNull(ticket)
				assertNull(shared.admit(shared.beginLogin()))
				failing.set(false)
				main { controller!!.logout() }
				await { shared.pendingLogoutTicket() == null && !controller!!.state.busy }
				main { controller!!.close(); controller = create(); controller!!.start() }
				await { !controller!!.state.busy }
				main { assertFalse(controller!!.state.logoutPending) }
			} finally {
				failing.set(false)
				main { controller?.close(); controller = null }
				shared.pendingLogoutTicket()?.let { shared.logout(it) }
			}
		}
	}

	@Test fun failedUiDeliveryRetriesWithoutAcknowledgingAnotherObserver() {
		val cleaned = CountDownLatch(1)
		val shared = SessionCoordinator { cleaned.countDown() }
		val attempts = java.util.concurrent.atomic.AtomicInteger()
		val retried = CountDownLatch(1)
		val held = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
		val delivered = CountDownLatch(1)
		val failing: (Long, () -> Unit) -> Unit = { _, acknowledge ->
			if (attempts.incrementAndGet() == 1) throw IllegalStateException("Synthetic delivery failure")
			retried.countDown()
			acknowledge()
		}
		val delayed: (Long, () -> Unit) -> Unit = { _, acknowledge ->
			held.set(acknowledge)
			delivered.countDown()
		}
		shared.observeUiRevocations(failing)
		shared.observeUiRevocations(delayed)
		val ticket = shared.cancelInFlightNow()
		val finished = CountDownLatch(1)
		val worker = kotlin.concurrent.thread {
			try { shared.logout(ticket) } finally { finished.countDown() }
		}
		try {
			assertTrue(delivered.await(10, TimeUnit.SECONDS))
			assertTrue(cleaned.await(10, TimeUnit.SECONDS))
			assertTrue(retried.await(10, TimeUnit.SECONDS))
			assertEquals(2, attempts.get())
			assertSame(ticket, shared.pendingLogoutTicket())
			assertNull(shared.admit(shared.beginLogin()))
			assertFalse("The remaining observer has not acknowledged", finished.await(1, TimeUnit.SECONDS))
			held.get()!!.invoke()
			assertTrue(finished.await(10, TimeUnit.SECONDS))
			assertNull(shared.pendingLogoutTicket())
		} finally {
			held.get()?.invoke()
			shared.removeUiRevocationObserver(failing)
			shared.removeUiRevocationObserver(delayed)
			worker.join(10000)
			shared.pendingLogoutTicket()?.let { shared.logout(it) }
		}
		assertFalse(worker.isAlive)
	}

	@Test fun failedUiObserverKeepsExpiryPendingUntilUnsubscribed() {
		val shared = SessionCoordinator { }
		val session = shared.activate(AccountId("synthetic-expiry"))
		val delivered = CountDownLatch(1)
		val observer: (Long, () -> Unit) -> Unit = { _, _ ->
			delivered.countDown()
			throw IllegalStateException("Synthetic observer failure")
		}
		shared.observeUiRevocations(observer)
		try {
			assertTrue(shared.revokeIfActive(session, expired = true))
			assertTrue(delivered.await(10, TimeUnit.SECONDS))
			assertNotNull(shared.pendingLogoutTicket())
			assertNull(shared.admit(shared.beginLogin()))
		} finally {
			shared.removeUiRevocationObserver(observer)
		}
		assertNull(shared.pendingLogoutTicket())
		val newer = shared.activate(AccountId("synthetic-new"))
		assertFalse(shared.revokeIfActive(session, expired = true))
		assertNotNull(shared.admit(newer))
		shared.logout()
	}

	@Test fun mainThreadRevocationAppliesAfterCurrentTurn() {
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
		main {
			controller = NativeController { emit -> SchoolRepository(gateway, IdentityVerifier { _, _ -> SessionVerification.Retry }, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
			coordinator.cancelInFlightNow()
			assertFalse(controller!!.state.logoutPending)
		}
		await { controller!!.state.logoutPending }
	}
	@Test fun delayedRestoreClearCannotEraseNewLoginDiary() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
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
		val store = SessionStore(context, coordinator)
		val seed = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		assertTrue(seed.login("1", charArrayOf('x')) is GatewayResult.Success)
		SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
		SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true }.setBoolean(null, true)
		lateinit var repository: SchoolRepository
		main {
			controller = NativeController { emit ->
				SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, { event ->
					if (Thread.currentThread().name == "old-restore") {
						assertFalse(Thread.holdsLock(org.materialclass.security.StorageLock))
						entered.countDown()
						check(release.await(60, TimeUnit.SECONDS))
					}
					emit(event)
				}, store).also { repository = it }
			}
		}
		val worker = kotlin.concurrent.thread(name = "old-restore") { repository.restore() }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repository.logout()
			val observed = NativeController::class.java.getDeclaredField("observedEpoch").apply { isAccessible = true }
			await { observed.getLong(controller) == coordinator.revocationEpoch() }
			await { coordinator.pendingLogoutTicket() == null && !controller!!.state.logoutPending }
			main { controller!!.login("2", charArrayOf('x')) }
			await { controller!!.state.signedIn && controller!!.state.diary is DiaryState.Ready }
			release.countDown()
			worker.join(10000)
			assertFalse(worker.isAlive)
			main { }
			main { assertTrue(controller!!.state.signedIn); assertTrue(controller!!.state.diary is DiaryState.Ready) }
		} finally { release.countDown(); worker.join(10000) }
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
			await { shared.pendingLogoutTicket() == null }
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

	@Test fun failedExpiryClearsBothControllersAndCanRetry() {
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
			val old = shared.event().session!!
			failing.set(true)
			try { shared.revokeIfActive(old, expired = true); fail("Expected cleanup failure") } catch (_: IllegalStateException) { }
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

	@Test fun liveControllersRejectDelayedContradictoryProfileAfterReplacement() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val returned = CountDownLatch(1)
		val block = java.util.concurrent.atomic.AtomicBoolean(false)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, listOf(DiaryDay("Thursday", context.date, listOf(DiarySubject("Diary ${session.account}", "lesson", "synthetic", "none", null, null, false, false))))))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(listOf(ScheduleDay(1, date, true, false, listOf(ScheduleLesson("lesson", 1, "Schedule ${session.account}"))))))
		}
		val verifier = IdentityVerifier { session, _ ->
			if (session.identityCandidate == "1" && block.get()) {
				entered.countDown()
				check(release.await(30, TimeUnit.SECONDS))
				returned.countDown()
				SessionVerification.Invalid
			} else {
				val data = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
				SessionVerification.Verified(AccountId(session.identityCandidate!!), true, VerifiedScheduleSession.compare(session, data), VerifiedDiarySession.compare(session, data), StudentProfile("Profile ${session.identityCandidate}", null, null, null))
			}
		}
		val cache = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? = null
			override fun write(session: ActiveSession, bytes: ByteArray) = true
		}
		val firstStates = mutableListOf<NativeUiState>()
		val secondStates = mutableListOf<NativeUiState>()
		fun create(states: MutableList<NativeUiState>) = NativeController({ states.add(it) }) { emit -> SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator)) }
		main { controller = create(firstStates) }
		val first = controller!!
		val second = create(secondStates)
		try {
			main { first.login("1", charArrayOf('x')) }
			await { first.state.profile?.surname == "Profile 1" && first.state.diary is DiaryState.Ready && first.state.schedule != null }
			block.set(true)
			main { first.refresh() }
			assertTrue(entered.await(15, TimeUnit.SECONDS))
			main { second.login("2", charArrayOf('x')) }
			await { second.state.profile?.surname == "Profile 2" && second.state.diary is DiaryState.Ready && second.state.schedule != null && !first.state.signedIn }
			var firstIndex = 0
			var secondIndex = 0
			main { firstIndex = firstStates.size; secondIndex = secondStates.size }
			release.countDown()
			assertTrue(returned.await(15, TimeUnit.SECONDS))
			val field = NativeController::class.java.getDeclaredField("executor").apply { isAccessible = true }
			(field.get(first) as java.util.concurrent.ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
			instrumentation.waitForIdleSync()
			main {
				assertTrue(second.state.signedIn)
				assertEquals("Profile 2", second.state.profile?.surname)
				assertTrue(firstStates.drop(firstIndex).none { it.signedIn || it.profile != null || it.diary is DiaryState.Ready || it.schedule != null })
				assertTrue(secondStates.drop(secondIndex).none { !it.signedIn || it.profile?.surname != "Profile 2" || it.diary !is DiaryState.Ready || it.schedule == null })
			}
		} finally { release.countDown(); main { second.close() } }
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
			main { other.logout() }
			await { coordinator.pendingLogoutTicket() == null }
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

	@Test fun currentWeekOfflineShowsEncryptedSavedSchedule() {
		val offline = java.util.concurrent.atomic.AtomicBoolean(false)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = if (offline.get()) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success("01.01.2026")
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule((1..7).map { ScheduleDay(0, "%02d.01.2026".format(it), true, false, listOf(ScheduleLesson("lesson", 1, "Saved lesson"))) }))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":1,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId("1"), true, VerifiedScheduleSession.compare(session, profile), VerifiedDiarySession.compare(session, profile))
		}
		val diary = EncryptedDiaryCache(org.materialclass.cache.EncryptedCache(context, coordinator))
		val schedule = EncryptedDiaryCache(org.materialclass.cache.EncryptedCache(context, coordinator, "schedule"))
		main { controller = NativeController { emit -> SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, emit, SessionStore(context, coordinator), schedule) }; controller!!.login("synthetic", charArrayOf('x')) }
		await { controller!!.state.scheduleSnapshot != null }
		offline.set(true)
		main { controller!!.refresh() }
		await { controller!!.state.scheduleSnapshot?.staleError == AppError.OFFLINE }
		main {
			val snapshot = controller!!.state.scheduleSnapshot!!
			assertEquals("01.01.2026", snapshot.date)
			assertEquals(1234L, snapshot.lastSuccessMillis)
			assertEquals(7, snapshot.schedule.days.size)
			assertTrue(snapshot.schedule.days.none { it.isCurrent })
		}
	}

}
