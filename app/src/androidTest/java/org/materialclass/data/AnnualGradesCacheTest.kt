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

class AnnualGradesCacheTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	@After fun cleanup() { coordinator.logout(); EncryptedCache(context, coordinator).clearAll() }

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
		val grades = EncryptedDiaryCache(EncryptedCache(context, coordinator, "grades"))
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
