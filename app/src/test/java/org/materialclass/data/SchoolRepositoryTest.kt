package org.materialclass.data

import org.junit.Assert.*
import org.junit.Test
import org.materialclass.api.*
import org.materialclass.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SchoolRepositoryTest {
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
		gateway, IdentityVerifier { if (verify) it.identityCandidate?.let(::AccountId) else null }, SessionCoordinator { }, cache, { 1234L }, events::add
	)

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
	fun cancellationIsNotConvertedToUnavailable() {
		val gateway = object : SchoolGateway {
			override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = throw java.util.concurrent.CancellationException()
			override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = error("unexpected")
		}
		val repository = SchoolRepository(gateway, IdentityVerifier { null }, SessionCoordinator { }, FakeCache(), { 0L }, {})
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
	fun offlineWithoutCacheIsErrorNotEmptySuccess() {
		val gateway = FakeGateway(); gateway.result = GatewayResult.Failure(AppError.OFFLINE)
		val events = mutableListOf<DiaryState>(); val repository = repository(gateway, FakeCache(), events)
		repository.login("synthetic", charArrayOf('x'))
		repository.refresh(context)
		assertEquals(AppError.OFFLINE, (events.last() as DiaryState.Error).error)
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
