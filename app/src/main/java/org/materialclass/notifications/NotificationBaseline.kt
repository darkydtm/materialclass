package org.materialclass.notifications

import android.content.Context
import org.materialclass.api.Diary
import org.materialclass.api.DiaryContext
import org.materialclass.cache.EncryptedCache
import org.materialclass.security.StorageUnreadableException
import org.materialclass.session.ActiveSession
import org.materialclass.session.SessionCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal sealed class BaselineResult {
	object Initialized : BaselineResult()
	object Unchanged : BaselineResult()
	object Invalid : BaselineResult()
	data class Change internal constructor(internal val context: DiaryContext, internal val previous: String, internal val current: String, internal val eventId: String) : BaselineResult()
}

internal class NotificationBaseline(context: Context, private val coordinator: SessionCoordinator) {
	private val cache = EncryptedCache(context, coordinator, "notification-baseline")
	private data class Entry(val context: DiaryContext, var fingerprint: String, var pending: String?, var eventId: String?)

	fun observe(session: ActiveSession, context: DiaryContext, diary: Diary): BaselineResult? {
		val observation = DiaryObservation.from(context, diary) ?: return BaselineResult.Invalid
		return coordinator.ifActive(session) {
			val entries = read(session)
			val entry = entries.firstOrNull { sameContext(it.context, context) }
			if (entry == null) {
				if (entries.size >= 32) throw StorageUnreadableException()
				entries.add(Entry(context, observation.fingerprint, null, null))
				check(cache.write(session, encode(entries)))
				BaselineResult.Initialized
			} else {
				val pending = entry.pending
				if (pending != null) BaselineResult.Change(entry.context, entry.fingerprint, pending, checkNotNull(entry.eventId))
				else if (entry.fingerprint == observation.fingerprint) BaselineResult.Unchanged
				else {
					entry.pending = observation.fingerprint
					entry.eventId = java.util.UUID.randomUUID().toString()
					check(cache.write(session, encode(entries)))
					BaselineResult.Change(entry.context, entry.fingerprint, observation.fingerprint, checkNotNull(entry.eventId))
				}
			}
		}
	}

	fun acknowledge(session: ActiveSession, change: BaselineResult.Change): Boolean = coordinator.ifActive(session) {
		val entries = read(session)
		val entry = entries.firstOrNull { sameContext(it.context, change.context) }
		if (entry == null || entry.fingerprint != change.previous || entry.pending != change.current || entry.eventId != change.eventId) false
		else {
			entry.fingerprint = change.current
			entry.pending = null
			entry.eventId = null
			cache.write(session, encode(entries))
		}
	} ?: false

	private fun sameContext(first: DiaryContext, second: DiaryContext): Boolean =
		first.schoolId == second.schoolId && first.eduYear == second.eduYear && first.groupId == second.groupId && first.date == second.date

	private fun read(session: ActiveSession): MutableList<Entry> {
		val bytes = cache.read(session) ?: return mutableListOf()
		try {
			require(bytes.size <= 32768)
			val input = DataInputStream(ByteArrayInputStream(bytes))
			require(input.readInt() == 2)
			val count = input.readInt()
			require(count in 0..32)
			val entries = mutableListOf<Entry>()
			repeat(count) {
				val context = DiaryContext(input.readLong(), input.readInt(), input.readLong(), input.readUTF())
				require(context.schoolId > 0 && context.eduYear > 0 && context.groupId > 0 && context.date.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")))
				val fingerprint = input.readUTF()
				val pending = if (input.readBoolean()) input.readUTF() else null
				val eventId = if (pending != null) input.readUTF() else null
				if (eventId != null) require(java.util.UUID.fromString(eventId).toString() == eventId)
				require(fingerprint.matches(Regex("[0-9a-f]{64}")) && (pending == null || pending.matches(Regex("[0-9a-f]{64}")) && pending != fingerprint))
				require(entries.none { sameContext(it.context, context) })
				entries.add(Entry(context, fingerprint, pending, eventId))
			}
			require(input.available() == 0)
			return entries
		} catch (error: Exception) { throw StorageUnreadableException(error) }
	}

	private fun encode(entries: List<Entry>): ByteArray {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use { out ->
			out.writeInt(2); out.writeInt(entries.size)
			entries.forEach { entry ->
				out.writeLong(entry.context.schoolId); out.writeInt(entry.context.eduYear); out.writeLong(entry.context.groupId); out.writeUTF(entry.context.date)
				out.writeUTF(entry.fingerprint); out.writeBoolean(entry.pending != null); entry.pending?.let { out.writeUTF(it); out.writeUTF(checkNotNull(entry.eventId)) }
			}
		}
		return bytes.toByteArray()
	}
}
