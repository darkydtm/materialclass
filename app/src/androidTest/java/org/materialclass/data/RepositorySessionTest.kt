package org.materialclass.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.session.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class RepositorySessionTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	private val gateway = object : TestSchoolGateway() {
		override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "424242424242", "student", SchoolContext(1, 2026, 2)))
		override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
	}
	private var accesses = 0
	private val cache = object : DiaryCache {
		override fun read(session: ActiveSession): ByteArray? { accesses++; return null }
		override fun write(session: ActiveSession, bytes: ByteArray): Boolean { accesses++; return true }
	}
	private val diaryContext = DiaryContext(1, 2026, 2, "01.01.2026")
	private fun repository(verifier: IdentityVerifier) = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
	private fun restart() {
		SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
		SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true }.setBoolean(null, true)
	}
	private fun proof(session: UnverifiedSession): SessionVerification.Verified {
		val profile = org.json.JSONObject("""{"userId":424242424242,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
		return SessionVerification.Verified(AccountId("424242424242"), true, diarySession = VerifiedDiarySession.compare(session, profile))
	}
	private fun saved() {
		assertTrue(repository(IdentityVerifier { session, _ -> proof(session) }).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
	}


	@After
	fun cleanup() { coordinator.logout() }

	@Test
	fun reentrantLoginInvalidatesRestoreClearWithoutRevokingNewSession() {
		saved()
		lateinit var repo: SchoolRepository
		var clear: DiaryState? = null
		repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, { event ->
			if (event is DiaryState.RestoreClear) {
				assertFalse(Thread.holdsLock(org.materialclass.security.StorageLock))
				assertTrue(repo.acceptsDiary(event))
				assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
				clear = event
			}
		}, store)
		assertEquals(RestoreResult.Cancelled, repo.restore())
		assertNotNull(clear)
		assertFalse(repo.acceptsDiary(clear!!))
		assertNotNull(repo.ownedEpoch())
	}

	@Test
	fun failedRestoreActivationCallbackRevokesSession() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> proof(session) })
		repo.afterRestoreActivation = { throw IllegalStateException("synthetic failure") }
		try { repo.restore(); fail("Callback failure must propagate") } catch (_: IllegalStateException) { }
		assertTrue("Failed restore must not remain active", coordinator.logoutIfNoActive())
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun failedRestoreEmissionRevokesAndClearsRepository() {
		saved()
		var fail = true
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, { if (fail) throw IllegalStateException("synthetic failure") }, store)
		try { repo.restore(); fail("Emission failure must propagate") } catch (_: IllegalStateException) { }
		fail = false
		assertTrue("Failed restore must not remain active", coordinator.logoutIfNoActive())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun logoutBeforeLoginAdmissionPreventsGatewayCall() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val fake = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				calls.incrementAndGet()
				return GatewayResult.Success(UnverifiedSession("synthetic-token", "424242424242", "student", SchoolContext(1, 2026, 2)))
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val repo = SchoolRepository(fake, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, {}, store)
		repo.beforeIdentityAdmission = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		val password = charArrayOf('x')
		val worker = thread { repo.login("synthetic", password) }
		try {
			assertTrue("Login must reach the admission barrier", entered.await(5, TimeUnit.SECONDS))
			repo.logout()
		} finally {
			release.countDown()
			worker.join(10000)
		}
		assertFalse(worker.isAlive)
		assertEquals(0, calls.get())
		assertArrayEquals(charArrayOf('\u0000'), password)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}


	@Test
	fun logoutBeforeProfileAdmissionPreventsVerification() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val admissions = java.util.concurrent.atomic.AtomicInteger()
		val repo = repository(IdentityVerifier { session, _ -> calls.incrementAndGet(); proof(session) })
		repo.beforeIdentityAdmission = {
			if (admissions.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		}
		val worker = thread { repo.login("synthetic", charArrayOf('x')) }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
		} finally { release.countDown(); worker.join(10000) }
		assertFalse(worker.isAlive)
		assertEquals(0, calls.get())
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun logoutBeforeRestoreProfileAdmissionPreventsVerification() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val calls = java.util.concurrent.atomic.AtomicInteger()
		val repo = repository(IdentityVerifier { session, _ -> calls.incrementAndGet(); proof(session) })
		repo.beforeIdentityAdmission = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
		var result: RestoreResult? = null
		val worker = thread { result = repo.restore() }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
		} finally { release.countDown(); worker.join(10000) }
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		assertEquals(0, calls.get())
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun logoutDisconnectsAdmittedProfileAndRejectsLateVerification() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = CountDownLatch(1)
		val verifier = object : IdentityVerifier {
			override fun verify(session: UnverifiedSession, lease: RequestLease): SessionVerification {
				check(lease.register { disconnected.countDown() })
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				return proof(session)
			}
		}
		val repo = repository(verifier)
		var result: GatewayResult<Unit>? = null
		val worker = thread { result = repo.login("synthetic", charArrayOf('x')) }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
			assertTrue(disconnected.await(10, TimeUnit.SECONDS))
		} finally { release.countDown(); worker.join(10000) }
		assertFalse(worker.isAlive)
		assertTrue(result is GatewayResult.Failure)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun verifiedLoginPersistsEncryptedSession() {
		val repository = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, { _: DiaryState -> }, store)
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val bytes = File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1)
		assertFalse(bytes.contains("synthetic-token"))
		assertFalse(bytes.contains("424242424242"))
	}
	@Test
	fun persistsExactSessionToken() {
		val exactGateway = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> =
				GatewayResult.Success(UnverifiedSession("exact-session-token", "424242424242", "student", SchoolContext(1, 2026, 2)))
		}
		val repo = SchoolRepository(exactGateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, {}, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val active = SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as ActiveSession
		val material = store.read(active)!!
		try {
			java.io.DataInputStream(java.io.ByteArrayInputStream(material)).use {
				assertEquals(4, it.readInt())
				assertEquals("424242424242", it.readUTF())
				assertEquals("exact-session-token", it.readUTF())
			}
		} finally { material.fill(0) }
	}

	@Test
	fun failedLoginClearsPreviousPrivateProfile() {
		val id = SessionCoordinator.privateStates.register()
		var allowVerification = true
		val profile = org.json.JSONObject("""{"userId":424242424242,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
		val verifier = IdentityVerifier { session, _ ->
			if (!allowVerification) SessionVerification.Retry
			else SessionVerification.Verified(
				AccountId("424242424242"),
				true,
				profile = StudentProfile("Verified", null, null, null),
				VerifiedDiarySession.compare(session, profile),
			)
		}
		val repo = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		repo.privateStateId = id
		try {
			assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
			assertEquals("Verified", SessionCoordinator.privateStates.read().states[id]?.profile?.surname)
			allowVerification = false
			assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
			assertEquals(NativeUiState(), SessionCoordinator.privateStates.read().states[id])
		} finally {
			SessionCoordinator.privateStates.unregister(id)
		}
	}

	@Test
	fun restorePublishesVerifiedProfileToPrivateRegistry() {
		val profile = org.json.JSONObject("""{"userId":424242424242,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
		val verifier = IdentityVerifier { session, _ ->
			SessionVerification.Verified(
				AccountId("424242424242"),
				true,
				StudentProfile("Restored", null, null, null),
				VerifiedDiarySession.compare(session, profile),
			)
		}
		assertTrue(SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
		val id = SessionCoordinator.privateStates.register()
		val repo = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, {}, store)
		repo.privateStateId = id
		try {
			assertTrue(repo.restore() is RestoreResult.Restored)
			assertEquals("Restored", SessionCoordinator.privateStates.read().states[id]?.profile?.surname)
		} finally {
			SessionCoordinator.privateStates.unregister(id)
		}
	}

	@Test
	fun restoreRequiresVerifiedMatchingStudent() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> proof(session) })
		assertTrue(repo.restore() is RestoreResult.Restored)
		repo.refresh(diaryContext)
		assertEquals(1, accesses)
		repo.logout()
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun offlineRestoreCannotOpenCache() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> SessionVerification.Retry })
		assertEquals(RestoreResult.Retry, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
	}

	@Test
	fun legacyTokenOnlySessionIsRevokedAndRequiresLogin() {
		assertTrue(repository(IdentityVerifier { session, _ -> proof(session) }).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val activeField = SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }
		val active = activeField.get(null) as ActiveSession
		assertTrue(store.write(active, "synthetic-token".toByteArray()))
		restart()
		var calls = 0
		val repo = repository(IdentityVerifier { session, _ -> calls++; SessionVerification.Retry })
		assertEquals(RestoreResult.Invalid, repo.restore())
		assertEquals(0, calls)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		restart()
		assertEquals(RestoreResult.Absent, repo.restore())
	}

	@Test
	fun versionOneSessionWithoutIdentityRequiresFreshLogin() {
		val verifier = IdentityVerifier { session, _ -> proof(session) }
		assertTrue(repository(verifier).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val active = SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as ActiveSession
		val bytes = java.io.ByteArrayOutputStream()
		java.io.DataOutputStream(bytes).use { it.writeInt(1); it.writeUTF("synthetic-token"); it.writeBoolean(false) }
		assertTrue(store.write(active, bytes.toByteArray()))
		restart()
		var calls = 0
		val repo = repository(IdentityVerifier { session, _ -> calls++; SessionVerification.Retry })
		assertEquals(RestoreResult.Invalid, repo.restore())
		assertEquals(0, calls)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		assertTrue(repository(verifier).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
		assertTrue(repository(verifier).restore() is RestoreResult.Restored)
	}

	@Test
	fun mismatchedIdentityCannotOpenCache() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> SessionVerification.Verified(AccountId("other"), true) })
		assertEquals(RestoreResult.Invalid, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun nonStudentCannotOpenCache() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> SessionVerification.Verified(AccountId("424242424242"), false) })
		assertEquals(RestoreResult.Invalid, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
	}

	@Test
	fun failedSessionWriteClearsPreviouslyEmittedDiary() {
		val events = mutableListOf<DiaryState>()
		var account = "424242424242"
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> if (account == "424242424242") proof(session) else SessionVerification.Verified(AccountId(account), true) }, coordinator, cache, { 1L }, { events.add(it) }, store)
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		repo.refresh(diaryContext)
		assertTrue(events.last() is DiaryState.Ready)
		val temporary = File(context.noBackupFilesDir, "storage-tmp")
		check(temporary.delete())
		check(temporary.createNewFile())
		account = "another-account"
		try {
			assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
			assertEquals(DiaryState.Cleared, events.last())
		} finally {
			check(temporary.delete())
			check(temporary.mkdir())
		}
	}

	@Test
	fun logoutCancelsRestoreAfterFailedLogin() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		var verifyingRestore = false
		val repo = repository(IdentityVerifier { session, _ ->
			if (!verifyingRestore) SessionVerification.Retry else {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				proof(session)
			}
		})
		assertTrue(repo.login("synthetic", charArrayOf('x')) is GatewayResult.Failure)
		verifyingRestore = true
		var result: RestoreResult? = null
		val worker = thread { result = repo.restore() }
		try {
			check(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
		} finally {
			release.countDown()
			worker.join(10000)
		}
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
	}

	@Test
	fun logoutAtRestoreActivationRevokesRestoredSession() {
		saved()
		val activated = CountDownLatch(1); val release = CountDownLatch(1)
		val logoutStarted = CountDownLatch(1); val logoutFinished = CountDownLatch(1)
		val events = java.util.Collections.synchronizedList(mutableListOf<DiaryState>())
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		repo.afterRestoreActivation = {
			assertFalse(Thread.holdsLock(org.materialclass.security.StorageLock))
			activated.countDown()
			check(release.await(10, TimeUnit.SECONDS))
		}
		var result: RestoreResult? = null
		val restoring = thread { result = repo.restore() }
		check(activated.await(10, TimeUnit.SECONDS))
		val loggingOut = thread {
			logoutStarted.countDown()
			try { repo.logout() } finally { logoutFinished.countDown() }
		}
		try {
			check(logoutStarted.await(10, TimeUnit.SECONDS))
			assertTrue("Logout must finish while callback is blocked", logoutFinished.await(5, TimeUnit.SECONDS))
		} finally {
			release.countDown()
			restoring.join(10000)
			loggingOut.join(10000)
		}
		assertFalse(restoring.isAlive)
		assertFalse(loggingOut.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		assertEquals(DiaryState.Cleared, events.lastOrNull())
	}

	@Test
	fun restoreCallbackFailureDoesNotRevokeNewLogin() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val events = java.util.Collections.synchronizedList(mutableListOf<DiaryState>())
		val repo = SchoolRepository(gateway, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		repo.afterRestoreActivation = {
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			throw IllegalStateException("synthetic failure")
		}
		var failure: Throwable? = null
		val worker = thread { try { repo.restore() } catch (error: Throwable) { failure = error } }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			repo.logout()
			assertTrue(repo.login("new", charArrayOf('x')) is GatewayResult.Success)
			val epoch = repo.ownedEpoch()
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive)
			assertTrue(failure is IllegalStateException)
			assertNotNull(epoch)
			assertEquals(epoch, repo.ownedEpoch())
			assertTrue(File(context.noBackupFilesDir, "session").exists())
			repo.refresh(diaryContext)
			assertEquals(1, accesses)
		} finally { release.countDown(); worker.join(10000) }
	}

	@Test
	fun restoreCallbackCanLogoutReentrantly() {
		saved()
		val repo = repository(IdentityVerifier { session, _ -> proof(session) })
		repo.afterRestoreActivation = { repo.logout() }
		assertEquals(RestoreResult.Cancelled, repo.restore())
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun logoutCancelsPendingRestore() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val repo = repository(IdentityVerifier { session, _ ->
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			proof(session)
		})
		var result: RestoreResult? = null
		val worker = thread { result = repo.restore() }
		check(entered.await(10, TimeUnit.SECONDS))
		repo.logout()
		release.countDown(); worker.join(10000)
		assertFalse(worker.isAlive)
		assertEquals(RestoreResult.Cancelled, result)
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun newLoginSupersedesPendingLogin() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val fake = object : TestSchoolGateway() {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
				if (login == "old") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); assertTrue(lease.isCancelled()) }
				return GatewayResult.Success(UnverifiedSession("synthetic-token", "424242424242", "student", SchoolContext(1, 2026, 2)))
			}
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(fake, IdentityVerifier { session, _ -> proof(session) }, coordinator, cache, { 1L }, events::add, store)
		var older: GatewayResult<Unit>? = null
		val worker = thread { older = repo.login("old", charArrayOf('x')) }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertTrue(repo.login("new", charArrayOf('x')) is GatewayResult.Success)
			val count = events.size
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive); assertTrue(older is GatewayResult.Failure); assertEquals(count, events.size)
		} finally { release.countDown(); worker.join(10000) }
	}

	@Test
	fun newLoginSupersedesPendingRestore() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val verifier = IdentityVerifier { session, lease ->
			if (Thread.currentThread().name == "restore-worker") {
				entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); assertTrue(lease.isCancelled())
			}
			proof(session)
		}
		val events = mutableListOf<DiaryState>()
		val repo = SchoolRepository(gateway, verifier, coordinator, cache, { 1L }, events::add, store)
		var result: RestoreResult? = null
		val worker = thread(name = "restore-worker") { result = repo.restore() }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			assertTrue(repo.login("new", charArrayOf('x')) is GatewayResult.Success)
			val count = events.size
			release.countDown(); worker.join(10000)
			assertFalse(worker.isAlive); assertEquals(RestoreResult.Cancelled, result); assertEquals(count, events.size)
		} finally { release.countDown(); worker.join(10000) }
	}

}
