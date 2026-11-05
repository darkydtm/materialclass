package org.materialclass.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.session.RequestLease
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class NetworkFailureContractTest {
	@Test
	fun loginKeepsAmbiguousResponsesUnknownAndClearsCredentials() {
		val responses = listOf(
			Response(200, """{"success":false}"""), Response(200, "not json"),
			Response(302, ""), Response(401, ""), Response(403, ""), Response(429, ""), Response(500, "")
		)
		responses.forEach { response ->
			var calls = 0
			var body: ByteArray? = null
			val gateway = NativeGateway(Exchange { request ->
				calls++
				assertEquals("api.bilimclass.kz", request.url.host)
				assertNull(request.headers["Authorization"])
				body = request.body
				response
			})
			val password = "synthetic-password".toCharArray()
			val failure = gateway.login("synthetic-user", password, RequestLease()) as GatewayResult.Failure
			assertEquals(if (response.status == 200) AppError.INCOMPATIBLE_RESPONSE else AppError.UNAVAILABLE, failure.error)
			assertEquals(1, calls)
			assertTrue(password.all { it == '\u0000' })
			assertTrue(body!!.all { it == 0.toByte() })
		}
	}

	@Test
	fun loginTransportErrorsClearCredentialsWithoutRetry() {
		listOf(SocketTimeoutException() to AppError.TIMEOUT, IOException() to AppError.OFFLINE).forEach { (exception, expected) ->
			var calls = 0
			var body: ByteArray? = null
			val gateway = NativeGateway(Exchange { request -> calls++; body = request.body; throw exception })
			val password = "synthetic-password".toCharArray()
			assertEquals(expected, (gateway.login("synthetic-user", password, RequestLease()) as GatewayResult.Failure).error)
			assertEquals(1, calls)
			assertTrue(password.all { it == '\u0000' })
			assertTrue(body!!.all { it == 0.toByte() })
		}
	}

	private class RedirectConnection(url: URL) : HttpsURLConnection(url) {
		val output = ByteArrayOutputStream()
		var disconnected = false
		override fun disconnect() { disconnected = true }
		override fun usingProxy() = false
		override fun connect() { }
		override fun getCipherSuite(): String = ""
		override fun getLocalCertificates(): Array<Certificate>? = null
		override fun getServerCertificates(): Array<Certificate> = emptyArray()
		override fun getPeerPrincipal(): Principal? = null
		override fun getLocalPrincipal(): Principal? = null
		override fun getOutputStream() = output
		override fun getResponseCode(): Int {
			assertFalse(instanceFollowRedirects)
			return 302
		}
	}

	@Test
	fun httpsTransportDoesNotFollowRedirectsWithCredentials() {
		var opens = 0
		lateinit var connection: RedirectConnection
		val exchange = HttpsExchange({ url -> opens++; RedirectConnection(url).also { connection = it } })
		val gateway = NativeGateway(exchange)
		val password = "synthetic-password".toCharArray()
		assertEquals(AppError.UNAVAILABLE, (gateway.login("synthetic-user", password, RequestLease()) as GatewayResult.Failure).error)
		assertEquals(1, opens)
		assertFalse(connection.instanceFollowRedirects)
		assertTrue(connection.disconnected)
		assertTrue(password.all { it == '\u0000' })
	}
}
