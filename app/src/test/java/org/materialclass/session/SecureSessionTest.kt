package org.materialclass.session

import java.nio.file.Files
import java.time.Instant
import org.materialclass.cache.CacheFiles
import org.materialclass.cache.CacheRead
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.api.UserInfo
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.security.KeyHandle
import org.materialclass.security.KeyProvider
import org.materialclass.security.SecureCipher

class SecureSessionTest {
	@Test
	fun tokenAndCacheAreUnavailableUntilTheSessionIsActive() {
		val fixture = Fixture()
		val account = AccountId("synthetic-a")
		val session = fixture.coordinator.activate(account, "synthetic-token", Instant.MAX)

		assertEquals(null, fixture.coordinator.tokenFor(ActiveSession(account, Generation(session.generation.value + 1))))
		assertTrue(fixture.coordinator.cache().write(session, byteArrayOf(1, 2, 3)))
		assertArrayEquals(byteArrayOf(1, 2, 3), (fixture.coordinator.cache().read(session) as CacheRead.Hit).payload)
	}

	@Test
	fun refreshTokenSurvivesEncryptedSessionRestoreAndRotation() {
		val fixture = Fixture()
		val session = fixture.coordinator.activate(AccountId("synthetic-a"), "access-a", Instant.MAX)
		assertTrue(fixture.coordinator.setRefreshToken(session, "refresh-a"))
		val restored = SessionCoordinator(fixture.store, fixture.files, fixture.keys, fixture.cipher, fixture.tasks)
		assertTrue(restored.restore(Instant.EPOCH))
		val restoredSession = restored.currentSession()!!
		assertEquals("refresh-a", restored.refreshTokenFor(restoredSession))
		assertTrue(restored.replaceTokens(restoredSession, "access-b", "refresh-b", Instant.MAX))
		assertEquals("refresh-b", restored.refreshTokenFor(restoredSession))
		assertEquals("access-b", restored.tokenFor(restoredSession))
	}

	@Test
	fun authenticatedUserContextSurvivesEncryptedSessionRestore() {
		val fixture = Fixture()
		val session = fixture.coordinator.activate(AccountId("synthetic-a"), "access-a", Instant.MAX)
		val user = UserInfo(
			userId = "synthetic-a",
			schoolId = "synthetic-school",
			groupId = "synthetic-group",
			eduYear = "2026",
			hasStudentContext = true,
		)

		assertTrue(fixture.coordinator.setRefreshToken(session, "refresh-a"))
		assertTrue(fixture.coordinator.setUserInfo(session, user))

		val restored = SessionCoordinator(fixture.store, fixture.files, fixture.keys, fixture.cipher, fixture.tasks)
		assertTrue(restored.restore(Instant.EPOCH))
		assertEquals(user, restored.userInfoFor(restored.currentSession()!!))
	}

	@Test
	fun logoutClosesAccessCancelsTasksAndDeletesTokenAndCache() {
		val fixture = Fixture()
		val session = fixture.coordinator.activate(AccountId("synthetic-a"), "synthetic-token", Instant.MAX)
		fixture.coordinator.cache().write(session, byteArrayOf(7, 8))

		fixture.coordinator.logout()

		assertNull(fixture.coordinator.currentSession())
		assertNull(fixture.coordinator.tokenFor(session))
		assertEquals(1, fixture.tasks.cancelCount)
		assertFalse(fixture.files.tokenFile().exists())
		assertTrue(fixture.files.cacheFiles().isEmpty())
		assertEquals(CacheRead.Unavailable, fixture.coordinator.cache().read(session))
	}

	@Test
	fun oldGenerationCannotWriteAfterLogoutAndNewAccountCannotReadOldCache() {
		val fixture = Fixture()
		val old = fixture.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.MAX)
		fixture.coordinator.cache().write(old, byteArrayOf(1))
		fixture.coordinator.logout()
		val current = fixture.coordinator.activate(AccountId("synthetic-b"), "token-b", Instant.MAX)

		assertFalse(fixture.coordinator.cache().write(old, byteArrayOf(9)))
		assertEquals(CacheRead.Unavailable, fixture.coordinator.cache().read(old))
		assertEquals(CacheRead.Missing, fixture.coordinator.cache().read(current))
		assertEquals("token-b", fixture.coordinator.tokenFor(current))
	}

	@Test
	fun expiredStoredSessionIsCleanedWithoutServerLogout() {
		val fixture = Fixture()
		fixture.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.ofEpochMilli(10))
		fixture.coordinator.cache().write(fixture.coordinator.currentSession()!!, byteArrayOf(4))

		val restored = SessionCoordinator(fixture.store, fixture.files, fixture.keys, fixture.cipher, fixture.tasks)
		assertFalse(restored.restore(Instant.ofEpochMilli(11)))
		assertNull(restored.currentSession())
		assertFalse(fixture.files.tokenFile().exists())
		assertTrue(fixture.files.cacheFiles().isEmpty())
		assertEquals(0, fixture.tasks.serverLogoutCount)
	}

	@Test
	fun missingKeyOrTamperedTokenIsUnrecoverableAndCleaned() {
		val fixture = Fixture()
		fixture.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.MAX)
		fixture.keys.dropTokenKey()

		val missingKeyRestore = SessionCoordinator(fixture.store, fixture.files, fixture.keys, fixture.cipher, fixture.tasks)
		assertFalse(missingKeyRestore.restore(Instant.EPOCH))
		assertFalse(fixture.files.tokenFile().exists())

		val second = Fixture()
		second.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.MAX)
		val bytes = Files.readAllBytes(second.files.tokenFile().toPath())
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
		Files.write(second.files.tokenFile().toPath(), bytes)

		assertFalse(SessionCoordinator(second.store, second.files, second.keys, second.cipher, second.tasks).restore(Instant.EPOCH))
		assertFalse(second.files.tokenFile().exists())
	}

	@Test
	fun tamperedCacheIsRejectedAndRemoved() {
		val fixture = Fixture()
		val session = fixture.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.MAX)
		assertTrue(fixture.coordinator.cache().write(session, byteArrayOf(3, 2, 1)))
		val file = fixture.files.cacheFiles().single()
		val bytes = Files.readAllBytes(file.toPath())
		bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
		Files.write(file.toPath(), bytes)

		assertEquals(CacheRead.Unavailable, fixture.coordinator.cache().read(session))
		assertFalse(file.exists())
	}

	@Test
	fun cacheWritesAreEncryptedAndLeaveNoTemporaryFiles() {
		val fixture = Fixture()
		val session = fixture.coordinator.activate(AccountId("synthetic-a"), "token-a", Instant.MAX)
		val cleartext = "synthetic cache payload".toByteArray()

		assertTrue(fixture.coordinator.cache().write(session, cleartext))
		val fileBytes = Files.readAllBytes(fixture.files.cacheFiles().single().toPath())
		assertFalse(fileBytes.contentEquals(cleartext))
		assertTrue(fixture.files.temporaryFiles().isEmpty())
	}

	private class Fixture {
		val files = CacheFiles(Files.createTempDirectory("materialclass-session-test").toFile())
		val keys = FakeKeyProvider()
		val cipher = FakeCipher(keys)
		val store = SessionStore(files, keys, cipher)
		val tasks = FakeTaskRegistry()
		val coordinator = SessionCoordinator(store, files, keys, cipher, tasks)
	}
}

private class FakeKeyProvider : KeyProvider {
	private val keyBytes = mutableMapOf<String, ByteArray>()
	private var tokenPresent = true

	override fun tokenKey(): KeyHandle {
		if (!tokenPresent) throw IllegalStateException("token key unavailable")
		return key("token")
	}

	override fun cacheKey(accountId: AccountId): KeyHandle = key("cache-${accountId.value}")

	override fun material(handle: KeyHandle): javax.crypto.SecretKey =
		throw UnsupportedOperationException("Fake provider exposes synthetic bytes through FakeCipher")

	override fun deleteAll() {
		keyBytes.clear()
		tokenPresent = true
	}

	fun dropTokenKey() {
		tokenPresent = false
		keyBytes.remove("token")
	}

	private fun key(alias: String): KeyHandle {
		keyBytes.putIfAbsent(alias, alias.toByteArray())
		return KeyHandle(alias)
	}

	fun bytes(handle: KeyHandle): ByteArray = keyBytes[handle.alias] ?: throw IllegalStateException("key unavailable")
}

private class FakeCipher(private val keys: FakeKeyProvider) : SecureCipher {
	override fun encrypt(key: KeyHandle, plaintext: ByteArray): ByteArray {
		val encrypted = plaintext.mapIndexed { index, byte -> (byte.toInt() xor keys.bytes(key)[index % keys.bytes(key).size].toInt()).toByte() }.toByteArray()
		return encrypted + mac(key, encrypted)
	}

	override fun decrypt(key: KeyHandle, ciphertext: ByteArray): ByteArray {
		if (ciphertext.size < 32) throw IllegalArgumentException("ciphertext too short")
		val split = ciphertext.size - 32
		val encrypted = ciphertext.copyOf(split)
		if (!mac(key, encrypted).contentEquals(ciphertext.copyOfRange(split, ciphertext.size))) throw IllegalArgumentException("ciphertext authentication failed")
		return encrypted.mapIndexed { index, byte -> (byte.toInt() xor keys.bytes(key)[index % keys.bytes(key).size].toInt()).toByte() }.toByteArray()
	}

	private fun mac(key: KeyHandle, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
		init(SecretKeySpec(keys.bytes(key), "HmacSHA256"))
		doFinal(value)
	}
}

private class FakeTaskRegistry : SessionTaskRegistry {
	var cancelCount = 0
	var serverLogoutCount = 0
	override fun cancelAll() { cancelCount++ }
}
