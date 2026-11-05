package org.materialclass.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.materialclass.api.*
import org.materialclass.cache.EncryptedCache
import org.materialclass.session.*
import org.materialclass.security.KeystoreKeys
import java.io.File

class AnnualGradesCacheTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	@After fun cleanup() { coordinator.logout(); EncryptedCache(context, coordinator).clearAll() }

	@Test fun storageFailuresDoNotPublishGradesOrStaleMetadata() {
		var offline = false
		var failRead = false
		var failWrite = false
		var rejectWrite = false
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("Unexpected diary request")
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> = if (offline) GatewayResult.Failure(AppError.OFFLINE) else GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Synthetic subject", listOf("4", null, null, null), null, null))))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":1,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId("1"), true, null, VerifiedDiarySession.compare(session, profile))
		}
		val encrypted = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
		val grades = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? {
				if (failRead) throw java.io.IOException("Synthetic read failure")
				return encrypted.read(session)
			}
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean {
				if (failWrite) throw java.io.IOException("Synthetic write failure")
				return !rejectWrite && encrypted.write(session, bytes)
			}
		}
		val diary = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val repository = SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, {}, SessionStore(context, coordinator), null, {}, grades)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		fun load(): AnnualGradesDelivery {
			var delivery: AnnualGradesDelivery? = null
			repository.annualGrades { delivery = it }
			return delivery!!
		}
		assertTrue(load().result is GatewayResult.Success)
		failWrite = true
		assertEquals(AppError.CACHE_UNREADABLE, (load().result as GatewayResult.Failure).error)
		failWrite = false; rejectWrite = true
		assertEquals(AppError.CACHE_UNREADABLE, (load().result as GatewayResult.Failure).error)
		rejectWrite = false; offline = true; failRead = true
		val failed = load()
		assertEquals(AppError.CACHE_UNREADABLE, (failed.result as GatewayResult.Failure).error)
		assertNull(failed.lastSuccessMillis)
		assertNull(failed.staleError)
		failRead = false
		val recovered = load()
		assertEquals("Synthetic subject", (recovered.result as GatewayResult.Success).value.rows.single().subjectName)
		assertEquals(1234L, recovered.lastSuccessMillis)
		assertEquals(AppError.OFFLINE, recovered.staleError)
	}

	private fun cleanupRejectsCompletedGradeWrite(switchAccount: Boolean) {
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("Unexpected diary request")
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> = GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Synthetic subject", listOf("4", null, null, null), null, null))))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, null, VerifiedDiarySession.compare(session, profile))
		}
		val diary = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val grades = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
		val repository = SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, {}, SessionStore(context, coordinator), null, {}, grades)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val entered = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		val pending = java.util.concurrent.atomic.AtomicReference<AnnualGradesDelivery>()
		val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
		val worker = Thread {
			try {
				repository.annualGrades { delivery ->
					pending.set(delivery)
					entered.countDown()
					check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
				}
			} catch (failure: Throwable) { error.set(failure) }
		}
		worker.start()
		try {
			assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
			val delivery = pending.get()!!
			val id = KeystoreKeys().namespace(delivery.active.cacheNamespace.value + ":grades", false)
			val file = File(context.filesDir, "backup-cache/$id")
			val alias = "cache-$id"
			assertTrue(file.isFile)
			assertTrue(KeystoreKeys().contains(alias))
			if (switchAccount) assertTrue(repository.login("2", charArrayOf('x')) is GatewayResult.Success) else repository.logout()
			assertFalse(repository.acceptsAnnualGrades(delivery))
			assertFalse(file.exists())
			assertFalse(KeystoreKeys().contains(alias))
		} finally {
			release.countDown()
			worker.join(15000)
		}
		assertFalse(worker.isAlive)
		assertNull(error.get())
	}

	@Test fun logoutRejectsGradeDeliveryAfterWrite() = cleanupRejectsCompletedGradeWrite(false)
	@Test fun accountSwitchRejectsGradeDeliveryAfterWrite() = cleanupRejectsCompletedGradeWrite(true)

	private fun cleanupWaitsForGradeWrite(switchAccount: Boolean) {
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("Unexpected diary request")
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> = GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Synthetic subject", listOf("4", null, null, null), null, null))))
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, null, VerifiedDiarySession.compare(session, profile))
		}
		val entered = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		val written = java.util.concurrent.CountDownLatch(1)
		val encrypted = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
		val grades = object : DiaryCache {
			override fun read(session: ActiveSession) = encrypted.read(session)
			override fun write(session: ActiveSession, bytes: ByteArray): Boolean {
				val result = encrypted.write(session, bytes)
				written.countDown()
				entered.countDown()
				check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
				return result
			}
		}
		val diary = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val repository = SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, {}, SessionStore(context, coordinator), null, {}, grades)
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val active = coordinator.event().session!!
		val id = KeystoreKeys().namespace(active.cacheNamespace.value + ":grades", false)
		val file = File(context.filesDir, "backup-cache/$id")
		val alias = "cache-$id"
		val delivery = java.util.concurrent.atomic.AtomicReference<AnnualGradesDelivery>()
		val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
		val writer = Thread { try { repository.annualGrades { delivery.set(it) } } catch (error: Throwable) { failure.set(error) } }
		val changer = Thread { try { if (switchAccount) check(repository.login("2", charArrayOf('x')) is GatewayResult.Success) else repository.logout() } catch (error: Throwable) { failure.set(error) } }
		writer.start()
		try {
			assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
			assertTrue(written.await(1, java.util.concurrent.TimeUnit.SECONDS))
			assertTrue(file.isFile)
			changer.start()
			val deadline = android.os.SystemClock.uptimeMillis() + 15000
			while (changer.state != Thread.State.BLOCKED && changer.isAlive && android.os.SystemClock.uptimeMillis() < deadline) Thread.yield()
			assertEquals(Thread.State.BLOCKED, changer.state)
		} finally {
			release.countDown()
			writer.join(15000)
			changer.join(15000)
		}
		assertFalse(writer.isAlive)
		assertFalse(changer.isAlive)
		assertNull(failure.get())
		assertFalse(file.exists())
		assertFalse(KeystoreKeys().contains(alias))
		delivery.get()?.let { assertFalse(repository.acceptsAnnualGrades(it)) }
	}

	@Test fun logoutWaitsForInProgressGradeWrite() = cleanupWaitsForGradeWrite(false)
	@Test fun accountSwitchWaitsForInProgressGradeWrite() = cleanupWaitsForGradeWrite(true)

	@Test fun populatedGradesAreDeletedOnAccountSwitchAndLogout() {
		var response: GatewayResult<AnnualGrades> = GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Synthetic subject", listOf("4", null, null, null), null, null))))
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("Unexpected diary request")
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> = response
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":${session.identityCandidate},"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId(session.identityCandidate!!), true, null, VerifiedDiarySession.compare(session, profile))
		}
		val diary = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val grades = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
		val repository = SchoolRepository(gateway, verifier, coordinator, diary, { 1234L }, {}, SessionStore(context, coordinator), null, {}, grades)
		fun load(): AnnualGradesDelivery {
			var result: AnnualGradesDelivery? = null
			repository.annualGrades { result = it }
			return result!!
		}
		fun storedGrade(): Pair<File, String> {
			val keys = KeystoreKeys()
			val active = load().active
			val id = keys.namespace(active.cacheNamespace.value + ":grades", false)
			val file = File(context.filesDir, "backup-cache/$id")
			val alias = "cache-$id"
			assertTrue(file.isFile)
			assertTrue(keys.contains(alias))
			return file to alias
		}
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		val (firstFile, firstAlias) = storedGrade()
		response = GatewayResult.Failure(AppError.OFFLINE)
		assertTrue(repository.login("2", charArrayOf('x')) is GatewayResult.Success)
		assertFalse(firstFile.exists())
		assertFalse(KeystoreKeys().contains(firstAlias))
		assertEquals(AppError.OFFLINE, (load().result as GatewayResult.Failure).error)
		response = GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Second synthetic subject", listOf("5", null, null, null), null, null))))
		val (secondFile, secondAlias) = storedGrade()
		assertNotEquals(firstAlias, secondAlias)
		repository.logout()
		assertFalse(secondFile.exists())
		assertFalse(KeystoreKeys().contains(secondAlias))
		response = GatewayResult.Failure(AppError.OFFLINE)
		assertTrue(repository.login("2", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(AppError.OFFLINE, (load().result as GatewayResult.Failure).error)
	}

	@Test fun offlineReturnsLastConfirmedAnnualGrades() {
		var response: GatewayResult<AnnualGrades> = GatewayResult.Success(AnnualGrades(listOf(AnnualGradeRow("Synthetic subject", listOf("4", null, null, null), null, ""))))
		val gateway = object : TestSchoolGateway(), AnnualGradesGateway {
			override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic", login, "student", SchoolContext(1, 2026, 2)))
			override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = error("Unexpected diary request")
			override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> = response
		}
		val verifier = IdentityVerifier { session, _ ->
			val profile = JSONObject("""{"userId":1,"role":"student","school_id":1,"group":{"id":2},"school":{"eduYears":[{"isCurrent":true,"eduYear":2026}]}}""")
			SessionVerification.Verified(AccountId("1"), true, null, VerifiedDiarySession.compare(session, profile))
		}
		val diary = EncryptedDiaryCache(EncryptedCache(context, coordinator))
		val encryptedGrades = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
		var corrupt = false
		var mismatch = false
		val grades = object : DiaryCache {
			override fun read(session: ActiveSession): ByteArray? {
				val bytes = encryptedGrades.read(session) ?: return null
				if (corrupt) return byteArrayOf(1, 2, 3)
				if (mismatch) bytes[11] = (bytes[11].toInt() xor 1).toByte()
				return bytes
			}
			override fun write(session: ActiveSession, bytes: ByteArray) = encryptedGrades.write(session, bytes)
		}
		val constructor = SchoolRepository::class.java.declaredConstructors.singleOrNull { it.parameterTypes.size == 10 && it.parameterTypes.last() == DiaryCache::class.java }
		assertNotNull("Annual grades must accept a separate encrypted cache", constructor)
		val repository = constructor!!.newInstance(gateway, verifier, coordinator, diary, { 1234L }, { _: DiaryState -> }, SessionStore(context, coordinator), null, {}, grades) as SchoolRepository
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		fun load(): AnnualGradesDelivery {
			var delivery: AnnualGradesDelivery? = null
			repository.annualGrades { delivery = it }
			return delivery!!
		}
		assertTrue(load().result is GatewayResult.Success)
		response = GatewayResult.Failure(AppError.OFFLINE)
		val stale = load()
		assertEquals("Synthetic subject", (stale.result as GatewayResult.Success).value.rows.single().subjectName)
		assertEquals(1234L, stale.javaClass.getMethod("getLastSuccessMillis").invoke(stale))
		assertEquals(AppError.OFFLINE, stale.javaClass.getMethod("getStaleError").invoke(stale))
		val row = (stale.result as GatewayResult.Success).value.rows.single()
		assertEquals(listOf("4", null, null, null), row.quarters)
		assertNull(row.yearScore); assertEquals("", row.finalScore)
		response = GatewayResult.Failure(AppError.TIMEOUT)
		assertEquals(AppError.TIMEOUT, load().staleError)
		corrupt = true
		assertEquals(AppError.TIMEOUT, (load().result as GatewayResult.Failure).error)
		corrupt = false; mismatch = true
		assertEquals(AppError.TIMEOUT, (load().result as GatewayResult.Failure).error)
		mismatch = false
		for (error in listOf(AppError.FORBIDDEN, AppError.UNAVAILABLE, AppError.INCOMPATIBLE_RESPONSE, AppError.SERVER)) {
			response = GatewayResult.Failure(error)
			assertEquals(error, (load().result as GatewayResult.Failure).error)
		}
		response = GatewayResult.Success(AnnualGrades(emptyList()))
		assertTrue((load().result as GatewayResult.Success).value.rows.isEmpty())
		response = GatewayResult.Failure(AppError.OFFLINE)
		assertTrue((load().result as GatewayResult.Success).value.rows.isEmpty())
		repository.logout()
		assertTrue(repository.login("1", charArrayOf('x')) is GatewayResult.Success)
		assertEquals(AppError.OFFLINE, (load().result as GatewayResult.Failure).error)
	}
}
