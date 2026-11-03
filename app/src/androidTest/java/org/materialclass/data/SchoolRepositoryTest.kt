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

	private val context = DiaryContext(1, 2026, 2, "01.01.2026")
	private class FakeGateway : SchoolGateway {
		var identity: String? = "a"
		var role = "student"
		var result: GatewayResult<Diary> = GatewayResult.Success(Diary("01.01.2026", emptyList()))
		var entered: CountDownLatch? = null
		var release: CountDownLatch? = null
		override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", identity, role))
		override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> {
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
		gateway, IdentityVerifier { if (verify && it.identityCandidate != null) SessionVerification.Verified(AccountId(it.identityCandidate), true) else SessionVerification.Retry }, coordinator, cache, { 1234L }, events::add, store
	)

	private fun proof(session: UnverifiedSession): SessionVerification.Verified {
		val id = session.identityCandidate!!
		val profile = org.json.JSONObject("""{"userId":$id,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
		return SessionVerification.Verified(AccountId(id), true, VerifiedScheduleSession.compare(session, profile))
	}

	@Test
	fun scheduleCannotPublishAcrossAccountSwitch() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String): GatewayResult<Schedule> {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { proof(it) }, coordinator, FakeCache(), { 1L }, {}, store)
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String): GatewayResult<Schedule> {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { proof(it) }, coordinator, FakeCache(), { 1L }, {}, store)
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "1", "student", SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
			override fun schedule(session: VerifiedScheduleSession, date: String): GatewayResult<Schedule> {
				if (date == "01.01.2026") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
				return GatewayResult.Success(Schedule(emptyList()))
			}
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { proof(it) }, coordinator, FakeCache(), { 1L }, {}, store)
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> {
				entered.countDown()
				if (login == "older") enteredOld.countDown()
				check((if (login == "older") releaseOld else releaseNew).await(10, TimeUnit.SECONDS))
				return GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student"))
			}
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { it.identityCandidate?.let { id -> SessionVerification.Verified(AccountId(id), true) } ?: SessionVerification.Retry }, coordinator, cache, { 1L }, events::add, store)
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
		assertEquals(setOf(AccountId("newer")), cache.values.keys)
		repository.logout()
	}

	@Test
	fun switchingAccountsClearsPreviouslyEmittedDiary() {
		val gateway = FakeGateway(); val events = mutableListOf<DiaryState>()
		val repository = repository(gateway, FakeCache(), events)
		repository.login("a", charArrayOf('x'))
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		gateway.identity = "b"
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
		val repository = SchoolRepository(FakeGateway(), IdentityVerifier { SessionVerification.Verified(AccountId("a"), true) }, failingCoordinator, FakeCache(), { 1L }, events::add, failingStore)
		assertTrue(repository.login("a", charArrayOf('x')) is GatewayResult.Success)
		repository.refresh(context)
		assertTrue(events.last() is DiaryState.Ready)
		failClear = true
		try {
			repository.logout()
			fail("Storage failure must propagate")
		} catch (_: java.io.IOException) {
			assertEquals(DiaryState.Cleared, events.last())
		}
	}

	@Test
	fun logoutOfStaleRepositoryDoesNotRevokeNewerLogin() {
		val gateway = FakeGateway(); val olderEvents = mutableListOf<DiaryState>(); val newerEvents = mutableListOf<DiaryState>()
		val older = repository(gateway, FakeCache(), olderEvents)
		assertTrue(older.login("a", charArrayOf('x')) is GatewayResult.Success)
		older.refresh(context)
		gateway.identity = "b"
		val newer = repository(gateway, FakeCache(), newerEvents)
		assertTrue(newer.login("b", charArrayOf('x')) is GatewayResult.Success)
		older.logout()
		assertEquals(DiaryState.Cleared, olderEvents.last())
		newer.refresh(context)
		assertTrue(newerEvents.last() is DiaryState.Ready)
	}

	@Test
	fun cancellationIsNotConvertedToUnavailable() {
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = throw java.util.concurrent.CancellationException()
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = error("unexpected")
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { SessionVerification.Retry }, coordinator, FakeCache(), { 0L }, {}, store)
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
		repository.logout()
		val count = events.size
		repository.refresh(context)
		assertEquals(count, events.size)
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
	fun olderRefreshCannotOverwriteNewerRefresh() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "a", "student"))
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> {
				if (Thread.currentThread().name == "older") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
					return GatewayResult.Failure(AppError.OFFLINE)
				}
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { SessionVerification.Verified(AccountId("a"), true) }, coordinator, FakeCache(), { 1234L }, events::add, store)
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> {
				if (login == "newer") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
				}
				return GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student"))
			}
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Failure(AppError.SESSION_EXPIRED)
		}
		val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { it.identityCandidate?.let { id -> SessionVerification.Verified(AccountId(id), true) } ?: SessionVerification.Retry }, coordinator, FakeCache(), { 1L }, events::add, store)
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
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", login, "student"))
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> {
				if (session.identityCandidate == "older") {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
					return GatewayResult.Failure(AppError.SESSION_EXPIRED)
				}
				return GatewayResult.Success(Diary(context.date, emptyList()))
			}
		}
		val cache = FakeCache(); val events = mutableListOf<DiaryState>()
		val repository = SchoolRepository(gateway, IdentityVerifier { it.identityCandidate?.let { id -> SessionVerification.Verified(AccountId(id), true) } ?: SessionVerification.Retry }, coordinator, cache, { 1L }, events::add, store)
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
		assertEquals(setOf(AccountId("newer")), cache.values.keys)
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
		gateway.identity = "b"
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
