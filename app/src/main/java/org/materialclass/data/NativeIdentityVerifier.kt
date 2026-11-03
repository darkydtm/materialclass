package org.materialclass.data

import org.json.JSONObject
import org.materialclass.api.Exchange
import org.materialclass.api.HttpsExchange
import org.materialclass.api.Request
import org.materialclass.api.UnverifiedSession
import org.materialclass.session.AccountId
import org.materialclass.session.SessionVerification
import java.io.IOException
import java.net.URL
import java.util.concurrent.CancellationException

internal class NativeIdentityVerifier(private val exchange: Exchange = HttpsExchange()) : IdentityVerifier {
	override fun verify(session: UnverifiedSession): SessionVerification {
		val school = session.context?.schoolId ?: return SessionVerification.Retry
		if (school <= 0 || session.token.isEmpty() || session.role != "student") return SessionVerification.Retry
		return try {
			val headers = mapOf("Authorization" to "Bearer ${session.token}", "x-school-id" to school.toString(), "Accept" to "application/json")
			val response = exchange.execute(Request(URL("https://api.bilimclass.kz/api/v2/os/user-info"), "GET", headers, null))
			if (response.status != 200) return SessionVerification.Retry
			val data = JSONObject(response.body).getJSONObject("data")
			if (data.get("role") != "student") return SessionVerification.Retry
			val raw = data.get("userId")
			if (raw !is Int && raw !is Long) return SessionVerification.Retry
			val id = raw.toString()
			if (session.identityCandidate != null && session.identityCandidate != id) return SessionVerification.Retry
			SessionVerification.Verified(AccountId(id), true)
		} catch (error: CancellationException) {
			throw error
		} catch (_: IOException) {
			SessionVerification.Retry
		} catch (_: Exception) {
			SessionVerification.Retry
		}
	}
}
