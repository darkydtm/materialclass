package org.materialclass.data

import java.time.Instant
import org.materialclass.cache.CacheRead
import org.materialclass.cache.EncryptedCache

class EncryptedSnapshotStore(
	private val cache: EncryptedCache,
	private val sessions: SessionAccess,
) : SnapshotStore {
	override fun read(session: ActiveSession): SnapshotStoreRead {
		if (sessions.currentSession() != session) return SnapshotStoreRead.Unavailable
		return when (val result = cache.read(session)) {
			is CacheRead.Hit -> try { SnapshotStoreRead.Hit(decode(result.payload)) } catch (_: Exception) { SnapshotStoreRead.Unavailable }
			CacheRead.Missing -> SnapshotStoreRead.Missing
			CacheRead.Unavailable -> SnapshotStoreRead.Unavailable
		}
	}
	override fun write(session: ActiveSession, slices: Map<String, CachedSlice>): Boolean = sessions.currentSession() == session && cache.write(session, encode(slices))
	private fun encode(slices: Map<String, CachedSlice>): ByteArray = java.io.ByteArrayOutputStream().also { output -> java.io.DataOutputStream(output).use { data -> data.writeInt(MAGIC); data.writeInt(slices.size); slices.toSortedMap().forEach { (key, slice) -> data.writeUTF(key); data.writeLong(slice.capturedAt.toEpochMilli()); data.writeInt(slice.payload.size); data.write(slice.payload) } } }.toByteArray()
	private fun decode(bytes: ByteArray): Map<String, CachedSlice> = java.io.DataInputStream(bytes.inputStream()).use { data ->
		if (data.readInt() != MAGIC) throw IllegalArgumentException("snapshot header")
		val count = data.readInt()
		if (count < 0 || count > MAX_SLICES) throw IllegalArgumentException("snapshot count")
		buildMap { repeat(count) { val key = data.readUTF(); val timestamp = Instant.ofEpochMilli(data.readLong()); val length = data.readInt(); if (length < 0 || length > MAX_SLICE_BYTES) throw IllegalArgumentException("snapshot size"); put(key, CachedSlice(ByteArray(length).also(data::readFully), timestamp)) } }
	}
	private companion object { const val MAGIC = 0x4D435332; const val MAX_SLICES = 64; const val MAX_SLICE_BYTES = 4 * 1024 * 1024 }
}
