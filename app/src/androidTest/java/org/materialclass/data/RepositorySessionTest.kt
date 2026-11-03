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
	private val gateway = object : SchoolGateway {
		override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "424242424242", "student", SchoolContext(1, 2026, 2)))
		override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
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
		assertTrue(repository(IdentityVerifier { proof(it) }).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		restart()
	}


	@After
	fun cleanup() { coordinator.logout() }

	@Test
	fun verifiedLoginPersistsEncryptedSession() {
		val constructor = SchoolRepository::class.java.declaredConstructors.firstOrNull { constructor ->
			constructor.parameterTypes.any { it == SessionStore::class.java }
		}
		assertNotNull("Repository must accept the secure session store", constructor)
		constructor!!.isAccessible = true
		val repository = constructor.newInstance(gateway, IdentityVerifier { proof(it) }, coordinator, cache, { 1L }, { _: DiaryState -> }, store) as SchoolRepository
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val bytes = File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1)
		assertFalse(bytes.contains("synthetic-token"))
		assertFalse(bytes.contains("424242424242"))
	}
	@Test
	fun restoreRequiresVerifiedMatchingStudent() {
		saved()
		val repo = repository(IdentityVerifier { proof(it) })
		assertTrue(repo.restore() is RestoreResult.Restored)
		repo.refresh(diaryContext)
		assertEquals(1, accesses)
		repo.logout()
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun offlineRestoreCannotOpenCache() {
		saved()
		val repo = repository(IdentityVerifier { SessionVerification.Retry })
		assertEquals(RestoreResult.Retry, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
	}

	@Test
	fun legacyTokenOnlySessionIsRevokedAndRequiresLogin() {
		assertTrue(repository(IdentityVerifier { proof(it) }).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val activeField = SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }
		val active = activeField.get(null) as ActiveSession
		assertTrue(store.write(active, "synthetic-token".toByteArray()))
		restart()
		var calls = 0
		val repo = repository(IdentityVerifier { calls++; SessionVerification.Retry })
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
		val verifier = IdentityVerifier { proof(it) }
		assertTrue(repository(verifier).login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val active = SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as ActiveSession
		val bytes = java.io.ByteArrayOutputStream()
		java.io.DataOutputStream(bytes).use { it.writeInt(1); it.writeUTF("synthetic-token"); it.writeBoolean(false) }
		assertTrue(store.write(active, bytes.toByteArray()))
		restart()
		var calls = 0
		val repo = repository(IdentityVerifier { calls++; SessionVerification.Retry })
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
		val repo = repository(IdentityVerifier { SessionVerification.Verified(AccountId("other"), true) })
		assertEquals(RestoreResult.Invalid, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
	}

	@Test
	fun nonStudentCannotOpenCache() {
		saved()
		val repo = repository(IdentityVerifier { SessionVerification.Verified(AccountId("424242424242"), false) })
		assertEquals(RestoreResult.Invalid, repo.restore())
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
	}

	@Test
	fun failedSessionWriteClearsPreviouslyEmittedDiary() {
		val events = mutableListOf<DiaryState>()
		var account = "424242424242"
		val repo = SchoolRepository(gateway, IdentityVerifier { if (account == "424242424242") proof(it) else SessionVerification.Verified(AccountId(account), true) }, coordinator, cache, { 1L }, { events.add(it) }, store)
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
		val repo = repository(IdentityVerifier {
			if (!verifyingRestore) SessionVerification.Retry else {
				entered.countDown()
				check(release.await(10, TimeUnit.SECONDS))
				proof(it)
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
		val repo = SchoolRepository(gateway, IdentityVerifier { proof(it) }, coordinator, cache, { 1L }, events::add, store)
		repo.afterRestoreActivation = {
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
			logoutFinished.await(1, TimeUnit.SECONDS)
		} finally {
			release.countDown()
			restoring.join(10000)
			loggingOut.join(10000)
		}
		assertFalse(restoring.isAlive)
		assertFalse(loggingOut.isAlive)
		assertTrue(result is RestoreResult.Cancelled || result is RestoreResult.Restored)
		repo.refresh(diaryContext)
		assertEquals(0, accesses)
		assertFalse(File(context.noBackupFilesDir, "session").exists())
		assertEquals(DiaryState.Cleared, events.lastOrNull())
	}

	@Test
	fun logoutCancelsPendingRestore() {
		saved()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val repo = repository(IdentityVerifier {
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			proof(it)
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

}
