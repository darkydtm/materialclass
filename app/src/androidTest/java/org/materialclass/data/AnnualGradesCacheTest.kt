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
