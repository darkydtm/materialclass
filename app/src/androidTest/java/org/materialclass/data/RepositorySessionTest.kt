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

@RunWith(AndroidJUnit4::class)
class RepositorySessionTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val coordinator = SessionCoordinator(context)
	private val store = SessionStore(context, coordinator)
	private val gateway = object : SchoolGateway {
		override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = GatewayResult.Success(UnverifiedSession("synthetic-token", "synthetic-account", "student"))
		override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
	}
	private val cache = object : DiaryCache {
		override fun read(session: ActiveSession): ByteArray? = null
		override fun write(session: ActiveSession, bytes: ByteArray): Boolean = true
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
		val repository = constructor.newInstance(gateway, IdentityVerifier { AccountId("synthetic-account") }, coordinator, cache, { 1L }, { _: DiaryState -> }, store) as SchoolRepository
		assertTrue(repository.login("synthetic", charArrayOf('x')) is GatewayResult.Success)
		val bytes = File(context.noBackupFilesDir, "session").readBytes().toString(Charsets.ISO_8859_1)
		assertFalse(bytes.contains("synthetic-token"))
		assertFalse(bytes.contains("synthetic-account"))
	}
}
