package org.materialclass.data

import org.json.JSONObject
import org.materialclass.api.Exchange
import org.materialclass.api.HttpsExchange
import org.materialclass.api.Request
import org.materialclass.api.UnverifiedSession
import org.materialclass.api.VerifiedScheduleSession
import org.materialclass.api.VerifiedDiarySession
import org.materialclass.session.AccountId
import org.materialclass.session.SessionVerification
import org.materialclass.session.RequestLease
import java.io.IOException
import java.net.URL
import java.util.concurrent.CancellationException

internal class NativeIdentityVerifier(private val exchange: Exchange = HttpsExchange()) : IdentityVerifier {
	override fun verify(session: UnverifiedSession, lease: RequestLease): SessionVerification = verifyRequest(session, lease)

	private fun verifyRequest(session: UnverifiedSession, lease: RequestLease): SessionVerification {
		val candidate = session.identityCandidate?.takeIf { it.toLongOrNull()?.let { id -> id > 0 && id.toString() == it } == true } ?: return SessionVerification.Invalid
		val school = session.context?.schoolId ?: return SessionVerification.Retry
		if (school <= 0 || session.token.isEmpty() || session.role != "student") return SessionVerification.Retry
		return try {
			val headers = mapOf("Authorization" to "Bearer ${session.token}", "x-school-id" to school.toString(), "Accept" to "application/json")
			val response = exchange.execute(Request(URL("https://api.bilimclass.kz/api/v2/os/user-info"), "GET", headers, null, lease))
			if (response.status != 200) return SessionVerification.Retry
			val data = JSONObject(response.body).getJSONObject("data")
			val role = (data.opt("role") as? String)?.takeIf { it.isNotBlank() } ?: return SessionVerification.Retry
			if (role == "teacher") return SessionVerification.Invalid
			if (role != "student") return SessionVerification.Retry
			val raw = data.get("userId")
			if (raw !is Int && raw !is Long) return SessionVerification.Retry
			if ((raw as Number).toLong() <= 0) return SessionVerification.Retry
			val id = raw.toString()
			if (candidate != id) return SessionVerification.Invalid
			val schedule = VerifiedScheduleSession.compare(session, data)
			val diary = if (session.context?.studentSchoolUuid != null && schedule == null) null else VerifiedDiarySession.compare(session, data)
			fun field(obj: JSONObject?, key: String): String? = (obj?.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
			val profile = if (diary == null) null else org.materialclass.api.StudentProfile(field(data, "surname"), field(data, "firstname"), field(data.optJSONObject("school"), "name"), field(data.optJSONObject("group"), "name"))
			SessionVerification.Verified(AccountId(id), true, schedule, diary, profile)
		} catch (error: CancellationException) {
			throw error
		} catch (_: IOException) {
			SessionVerification.Retry
		} catch (_: Exception) {
			SessionVerification.Retry
		}
	}
}
