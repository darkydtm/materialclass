package org.materialclass.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.materialclass.api.*
import org.materialclass.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LoginLogoutCallbackTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	private val gateway = object : TestSchoolGateway() {
		override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> =
			GatewayResult.Success(UnverifiedSession("synthetic", "1", "student", SchoolContext(1, 2026, 2)))
		override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("unexpected")
	}
	private val cache = object : DiaryCache {
		override fun read(session: ActiveSession): ByteArray? = null
		override fun write(session: ActiveSession, bytes: ByteArray): Boolean = true
	}
	private fun repository(emit: (DiaryState) -> Unit) = SchoolRepository(
		gateway, IdentityVerifier { _, _ -> SessionVerification.Verified(AccountId("1"), true) },
		coordinator, cache, { 1L }, emit, store
	)

	@After fun cleanup() { coordinator.logout() }

	@Test fun blockedLoginCallbackDoesNotBlockLogout() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val done = CountDownLatch(1)
		val first = AtomicBoolean(true)
		val result = AtomicReference<GatewayResult<Unit>>()
		val repo = repository { if (first.getAndSet(false)) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) } }
		val login = thread { result.set(repo.login("1", charArrayOf('x'))) }
		var logout: Thread? = null
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			logout = thread { try { repo.logout() } finally { done.countDown() } }
			assertTrue(done.await(10, TimeUnit.SECONDS))
		} finally {
			release.countDown(); login.join(10000); logout?.join(10000)
		}
		assertFalse(login.isAlive)
		assertTrue(result.get() is GatewayResult.Failure)
		assertTrue(coordinator.logoutIfNoActive())
	}

	@Test fun throwingOldCallbackDoesNotRevokeReentrantLogin() {
		val first = AtomicBoolean(true)
		lateinit var repo: SchoolRepository
		var inner: GatewayResult<Unit>? = null
		repo = repository {
			if (first.getAndSet(false)) {
				inner = repo.login("new", charArrayOf('x'))
				throw IllegalStateException("callback failed")
			}
		}
		assertTrue(repo.login("old", charArrayOf('x')) is GatewayResult.Failure)
		assertTrue(inner is GatewayResult.Success)
		assertFalse(coordinator.logoutIfNoActive())
	}

	@Test fun throwingLoginCallbackRevokesSession() {
		val repo = repository { throw IllegalStateException("callback failed") }
		assertTrue(repo.login("1", charArrayOf('x')) is GatewayResult.Failure)
		assertTrue(coordinator.logoutIfNoActive())
	}
}
