package org.materialclass.data

import java.time.Instant
import org.materialclass.diagnostics.DiaryDiagnostics
import org.materialclass.diagnostics.DiaryOutcome
import org.materialclass.diagnostics.DiaryStage
import org.materialclass.cache.CacheRead
import org.materialclass.cache.EncryptedCache

class EncryptedSnapshotStore(
	private val cache: EncryptedCache,
	private val sessions: SessionAccess,
) : SnapshotStore {
	override fun read(session: ActiveSession): SnapshotStoreRead {
		if (sessions.currentSession() != session) return SnapshotStoreRead.Unavailable
		return when (val result = cache.read(session)) {
			is CacheRead.Hit -> runCatching { SnapshotStoreRead.Hit(decode(result.payload)) }
				.getOrDefault(SnapshotStoreRead.Unavailable)
			CacheRead.Missing -> SnapshotStoreRead.Missing
			CacheRead.Unavailable -> SnapshotStoreRead.Unavailable
		}
	}
	override fun write(session: ActiveSession, slices: Map<String, CachedSlice>): Boolean {
		if (sessions.currentSession() != session) return false
		return runCatching { cache.write(session, encode(slices)) }.onFailure { exception ->
			DiaryDiagnostics.record(DiaryStage.SNAPSHOT, DiaryOutcome.FAILURE, exception = exception)
		}.getOrDefault(false)
	}

	private fun encode(slices: Map<String, CachedSlice>): ByteArray {
		validateSlices(slices)
		return java.io.ByteArrayOutputStream().also { output ->
			java.io.DataOutputStream(output).use { data ->
				data.writeInt(MAGIC)
				data.writeInt(slices.size)
				slices.toSortedMap().forEach { (key, slice) ->
					data.writeUTF(key)
					data.writeLong(slice.capturedAt.toEpochMilli())
					data.writeInt(slice.payload.size)
					data.write(slice.payload)
				}
			}
		}.toByteArray()
	}

	private fun decode(bytes: ByteArray): Map<String, CachedSlice> = java.io.DataInputStream(bytes.inputStream()).use { data ->
		if (data.readInt() != MAGIC) throw IllegalArgumentException("snapshot header")
		val count = data.readInt()
		if (count < 0 || count > MAX_SLICES) throw IllegalArgumentException("snapshot count")
		val slices = LinkedHashMap<String, CachedSlice>(count)
		var totalBytes = 0L
		repeat(count) {
			val key = data.readUTF()
			if (key.isBlank() || slices.containsKey(key)) throw IllegalArgumentException("snapshot key")
			val timestamp = Instant.ofEpochMilli(data.readLong())
			val length = data.readInt()
			if (length < 0 || length > MAX_SLICE_BYTES) throw IllegalArgumentException("snapshot size")
			totalBytes += length
			if (totalBytes > MAX_TOTAL_SLICE_BYTES) throw IllegalArgumentException("snapshot total size")
			slices[key] = CachedSlice(ByteArray(length).also(data::readFully), timestamp)
		}
		if (data.available() != 0) throw IllegalArgumentException("snapshot trailing data")
		slices
	}

	private fun validateSlices(slices: Map<String, CachedSlice>) {
		if (slices.size > MAX_SLICES) throw IllegalArgumentException("snapshot count")
		var totalBytes = 0L
		for ((key, slice) in slices) {
			if (key.isBlank()) throw IllegalArgumentException("snapshot key")
			if (key.toByteArray(Charsets.UTF_8).size > MAX_UTF_BYTES) throw IllegalArgumentException("snapshot key")
			if (slice.payload.size > MAX_SLICE_BYTES) throw IllegalArgumentException("snapshot size")
			totalBytes += slice.payload.size
		}
		if (totalBytes > MAX_TOTAL_SLICE_BYTES) throw IllegalArgumentException("snapshot total size")
	}

	private companion object {
		const val MAGIC = 0x4D435332
		const val MAX_SLICES = 64
		const val MAX_SLICE_BYTES = 4 * 1024 * 1024
		const val MAX_TOTAL_SLICE_BYTES = 16 * 1024 * 1024
		const val MAX_UTF_BYTES = 65_535
	}
}
