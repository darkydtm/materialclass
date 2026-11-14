package org.materialclass.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import org.materialclass.api.UserInfo
import org.materialclass.cache.CacheFiles
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.Generation
import org.materialclass.security.KeyProvider
import org.materialclass.security.SecureCipher

class SessionStore(
	private val files: CacheFiles,
	private val keys: KeyProvider,
	private val cipher: SecureCipher,
) {
	fun save(
		session: ActiveSession,
		token: String,
		expiresAt: Instant,
		refreshToken: String? = null,
		userInfo: UserInfo? = null,
	) {
		require(token.isNotEmpty()) { "Token must not be empty" }
		val plaintext = ByteArrayOutputStream().also { output ->
			DataOutputStream(output).use { data ->
				data.writeInt(PAYLOAD_MAGIC)
				data.writeUTF(token)
				data.writeUTF(refreshToken.orEmpty())
				data.writeUserInfo(userInfo)
			}
		}.toByteArray()
		val encrypted = cipher.encrypt(keys.tokenKey(), plaintext)
		val record = ByteArrayOutputStream().also { output ->
			DataOutputStream(output).use { data ->
				data.writeInt(MAGIC)
				data.writeUTF(session.accountId.value)
				data.writeLong(session.generation.value)
				data.writeUTF(expiresAt.toString())
				data.writeInt(encrypted.size)
				data.write(encrypted)
			}
		}.toByteArray()
		files.atomicWrite(files.tokenFile(), record)
	}

	fun load(): StoredSession? {
		val file = files.tokenFile()
		if (!file.isFile) return null
		return try {
			DataInputStream(file.inputStream()).use { data ->
				if (data.readInt() != MAGIC) return null
				val account = AccountId(data.readUTF())
				val generation = Generation(data.readLong())
				val expiresAt = Instant.parse(data.readUTF())
				val length = data.readInt()
				if (length <= 0 || length > MAX_RECORD_BYTES) return null
				val encrypted = ByteArray(length)
				data.readFully(encrypted)
				if (data.available() != 0) return null
				val (token, refreshToken, userInfo, hasTrailingData) = DataInputStream(
					ByteArrayInputStream(cipher.decrypt(keys.tokenKey(), encrypted)),
				).use { input ->
					val version = input.readInt()
					if (version != PAYLOAD_MAGIC && version != LEGACY_PAYLOAD_MAGIC) throw IllegalArgumentException("unsupported session payload")
					val token = input.readUTF()
					val refreshToken = input.readUTF().takeIf(String::isNotEmpty)
					DecodedPayload(token, refreshToken, input.readUserInfo(version == PAYLOAD_MAGIC), input.available() != 0)
				}
				if (hasTrailingData) return null
				if (token.isEmpty()) return null
				StoredSession(ActiveSession(account, generation), token, refreshToken, expiresAt, userInfo)
			}
		} catch (_: Exception) {
			null
		}
	}

	fun clear() { files.tokenFile().delete() }

	private companion object {
		const val MAGIC = 0x4D435331
		const val LEGACY_PAYLOAD_MAGIC = 0x4D435332
		const val PAYLOAD_MAGIC = 0x4D435333
		const val MAX_RECORD_BYTES = 1024 * 1024
	}
}

private data class DecodedPayload(
	val token: String,
	val refreshToken: String?,
	val userInfo: UserInfo?,
	val hasTrailingData: Boolean,
)

data class StoredSession(
	val session: ActiveSession,
	val token: String,
	val refreshToken: String?,
	val expiresAt: Instant,
	val userInfo: UserInfo? = null,
)

private fun DataOutputStream.writeUserInfo(userInfo: UserInfo?) {
	writeBoolean(userInfo != null)
	if (userInfo == null) return
	writeUTF(userInfo.userId)
	writeNullable(userInfo.fullName)
	writeNullable(userInfo.schoolName)
	writeNullable(userInfo.region)
	writeNullable(userInfo.groupName)
	writeNullable(userInfo.schoolId)
	writeNullable(userInfo.groupId)
	writeNullable(userInfo.studentSchoolUuid)
	writeNullable(userInfo.studentGroupUuid)
	writeNullable(userInfo.eduYear)
	writeNullable(userInfo.chatToken)
	writeNullable(userInfo.role)
	writeBoolean(userInfo.hasStudentContext)
	writeInt(userInfo.studentGroupUuids.size)
	userInfo.studentGroupUuids.forEach(::writeUTF)
}

private fun DataOutputStream.writeNullable(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataInputStream.readUserInfo(hasGroupList: Boolean): UserInfo? {
	if (!readBoolean()) return null
	val user = UserInfo(
		userId = readUTF(),
		fullName = readNullable(),
		schoolName = readNullable(),
		region = readNullable(),
		groupName = readNullable(),
		schoolId = readNullable(),
		groupId = readNullable(),
		studentSchoolUuid = readNullable(),
		studentGroupUuid = readNullable(),
		eduYear = readNullable(),
		chatToken = readNullable(),
		role = readNullable(),
		hasStudentContext = readBoolean(),
	)
	if (!hasGroupList) return user.copy(studentGroupUuids = listOfNotNull(user.studentGroupUuid?.takeIf(String::isNotBlank)))
	val count = readInt()
	require(count in 0..10000) { "Invalid student group count" }
	return user.copy(studentGroupUuids = List(count) { readUTF() })
}

private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null
