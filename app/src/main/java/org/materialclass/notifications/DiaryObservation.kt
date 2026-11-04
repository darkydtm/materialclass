package org.materialclass.notifications

import org.materialclass.api.Diary
import org.materialclass.api.DiaryContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Compare only complete, validated snapshots within the same authenticated context.
internal data class DiaryObservation private constructor(val fingerprint: String) {
	companion object {
		fun from(context: DiaryContext, diary: Diary): DiaryObservation? = try {
			require(context.schoolId > 0 && context.eduYear > 0 && context.groupId > 0)
			require(validDate(context.date) && diary.date == context.date && diary.days.size <= 7)
			require(diary.days.map { it.date }.toSet().size == diary.days.size)
			val bytes = ByteArrayOutputStream()
			DataOutputStream(bytes).use { out ->
				out.writeInt(1)
				out.writeLong(context.schoolId); out.writeInt(context.eduYear); out.writeLong(context.groupId); out.writeUTF(context.date)
				out.writeInt(diary.days.size)
				diary.days.sortedBy { it.date }.forEach { day ->
					require(validDate(day.date) && day.day.length in 1..256 && day.subjects.size <= 512)
					out.writeUTF(day.date); out.writeUTF(day.day); out.writeInt(day.subjects.size)
					day.subjects.forEach { subject ->
						val values = listOf(subject.label, subject.type, subject.scheduleUuid, subject.homeworkType, subject.homeworkBody, subject.homeworkUuid)
						require(values.take(4).all { !it.isNullOrEmpty() } && values.all { it == null || it.length <= 16000 })
						values.forEach { value -> out.writeBoolean(value != null); if (value != null) out.writeUTF(value) }
						out.writeBoolean(subject.hasBooks); out.writeBoolean(subject.hasFiles)
					}
				}
			}
			val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
			DiaryObservation(digest.joinToString("") { "%02x".format(it.toInt() and 255) })
		} catch (_: Exception) { null }

		private fun validDate(value: String): Boolean {
			if (!value.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return false
			val format = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
			val position = ParsePosition(0)
			val date = format.parse(value, position) ?: return false
			return position.index == value.length && format.format(date) == value
		}
	}
}
