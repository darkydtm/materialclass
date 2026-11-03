package org.materialclass.api

import org.junit.Assert.*
import org.junit.Test
import org.materialclass.session.SessionCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

class HttpsCancellationTest {
	private val url = URL("https://api.bilimclass.kz/test")
	private class Connection(url: URL) : HttpsURLConnection(url) {
		var responses = 0
		var outputs = 0
		val disconnected = CountDownLatch(1)
		var entered: CountDownLatch? = null
		var release: CountDownLatch? = null
		override fun disconnect() { disconnected.countDown() }
		override fun usingProxy() = false
		override fun connect() {}
		override fun getCipherSuite(): String = ""
		override fun getLocalCertificates(): Array<Certificate>? = null
		override fun getServerCertificates(): Array<Certificate> = emptyArray()
		override fun getPeerPrincipal(): Principal? = null
		override fun getLocalPrincipal(): Principal? = null
		override fun getOutputStream(): java.io.OutputStream { outputs++; return ByteArrayOutputStream() }
		override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))
		override fun getResponseCode(): Int {
			responses++
			entered?.countDown()
			release?.let { check(it.await(10, TimeUnit.SECONDS)) }
			return 200
		}
	}

	@Test fun cancellationBeforeRegistrationPreventsConnection() {
		val coordinator = SessionCoordinator { }
		val lease = coordinator.admit(coordinator.beginLogin())!!
		coordinator.beginLogin()
		var opened = false
		try {
			HttpsExchange({ opened = true; Connection(it) }).execute(Request(url, "GET", emptyMap(), null, lease))
			fail("Cancelled request executed")
		} catch (_: IOException) { }
		assertFalse(opened)
	}

	@Test fun cancellationAfterRegistrationPreventsOutputAndResponse() {
		val coordinator = SessionCoordinator { }
		val lease = coordinator.admit(coordinator.beginLogin())!!
		val connection = Connection(url)
		try {
			HttpsExchange({ connection }, { coordinator.beginLogin() }).execute(Request(url, "POST", emptyMap(), byteArrayOf(1), lease))
			fail("Cancelled request executed")
		} catch (_: IOException) { }
		assertEquals(0, connection.outputs)
		assertEquals(0, connection.responses)
		assertTrue(connection.disconnected.await(10, TimeUnit.SECONDS))
	}

	@Test fun cancellationDisconnectsInFlightConnection() {
		val coordinator = SessionCoordinator { }
		val lease = coordinator.admit(coordinator.beginLogin())!!
		val connection = Connection(url)
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		connection.entered = entered; connection.release = release
		val worker = thread { try { HttpsExchange({ connection }).execute(Request(url, "GET", emptyMap(), null, lease)) } catch (_: IOException) { } }
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			coordinator.beginLogin()
			assertTrue(connection.disconnected.await(10, TimeUnit.SECONDS))
		} finally { release.countDown(); worker.join(10000) }
		assertFalse(worker.isAlive)
	}
}
