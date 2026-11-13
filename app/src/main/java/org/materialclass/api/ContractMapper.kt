package org.materialclass.api

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.materialclass.data.DiaryDay
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiarySubject
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.GradeSchedule
import org.materialclass.data.GradeSubject
import org.materialclass.data.GradesModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.ScheduleDay
import org.materialclass.data.ScheduleLesson
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SubjectsModel

/** Map only fields observed in the confirmed responses. */
object ContractMapper {
	fun mapLoginResponse(body: String): GatewayLoginResult = try {
		val root = JsonParser(body).parseObject()
		val accessToken = root.requiredString("access_token")
		val refreshToken = root.requiredString("refresh_token")
		val expiresAt = parseInstant(root.requiredString("exp_time"))
		val userInfo = root.requiredObject("user_info")
		val userId = userInfo["userId"]?.asText()?.takeIf { it.isNotBlank() }
			?: throw IllegalArgumentException("unsupported userId shape")
		if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank()) {
			throw IllegalArgumentException("blank observed field")
		}
		GatewayLoginResult.Success(LoginResponse(accessToken, refreshToken, expiresAt, UserInfo(userId)))
	} catch (_: Exception) {
		GatewayLoginResult.Failure(AppError.InvalidResponse)
	}

	fun mapDiaryWeeks(body: String): GatewayReadResult<DiaryWeeksModel> = try {
		val data = JsonParser(body).parseObject().requiredObject("data")
		val weeks = data.requiredArray("weeks")
		val periods = data.requiredArray("periods")
		if (weeks.any { it !is JsonValue.ObjectValue || !it.values.containsKey("label") || !it.values.containsKey("value") }) {
			throw IllegalArgumentException("unsupported week shape")
		}
		GatewayReadResult.Success(DiaryWeeksModel(weeks.size, periods.size))
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

	fun mapDiary(body: String): GatewayReadResult<DiaryModel> = try {
		val data = JsonParser(body).parseObject().requiredObject("data")
		val days = data.requiredArray("days")
		if (days.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported day shape")
		if (days.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(
			DiaryModel(days.size, data["date"]?.asText(), days.mapNotNull { value ->
				(val item = (value as JsonValue.ObjectValue).values).takeIf { it.isNotEmpty() }?.toDiaryDay()
			}),
		)
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

	fun mapDiaryPeriods(body: String): GatewayReadResult<PeriodsModel> = try {
		val periods = JsonParser(body).parseObject().requiredObject("data").requiredArray("periods")
		if (periods.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported period shape")
		if (periods.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(PeriodsModel(periods.size))
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

	fun mapSubjects(body: String): GatewayReadResult<SubjectsModel> = try {
		val subjects = JsonParser(body).parseObject().requiredArray("data")
		if (subjects.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported subject shape")
		if (subjects.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(SubjectsModel(subjects.size))
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

	fun mapSchedule(body: String): GatewayReadResult<ScheduleModel> = try {
		val days = JsonParser(body).parseObject().requiredObject("data").requiredArray("days")
		if (days.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported schedule day shape")
		val scheduledDays = days.count { it is JsonValue.ObjectValue && it.values["schedule"] is JsonValue.ArrayValue }
		if (days.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(
			ScheduleModel(days.size, scheduledDays, days.mapNotNull { value ->
				(val item = (value as JsonValue.ObjectValue).values).takeIf { it.isNotEmpty() }?.toScheduleDay()
			}),
		)
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

	fun mapGrades(body: String): GatewayReadResult<GradesModel> = try {
		val subjects = JsonParser(body).parseObject().requiredArray("data")
		if (subjects.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported grade shape")
		if (subjects.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(
			GradesModel(subjects.size, subjects.map { (it as JsonValue.ObjectValue).values.toGradeSubject() }),
		)
	} catch (_: Exception) {
		GatewayReadResult.Failure(AppError.InvalidResponse)
	}

}

data class LoginResponse internal constructor(
	internal val accessToken: String,
	internal val refreshToken: String,
	internal val expiresAt: Instant,
	val userInfo: UserInfo,
) {
	override fun toString(): String = "LoginResponse(userInfo=$userInfo)"
}

data class UserInfo(
	val userId: String,
	val fullName: String? = null,
	val schoolName: String? = null,
	val region: String? = null,
	val groupName: String? = null,
	val schoolId: String? = null,
	val groupId: String? = null,
	val studentSchoolUuid: String? = null,
	val studentGroupUuid: String? = null,
	val eduYear: String? = null,
	val role: String? = null,
) {
	override fun toString(): String = "UserInfo(userId=<redacted>)"
}

private fun Map<String, JsonValue>.toUserInfo(userId: String): UserInfo {
	val school = (get("school") as? JsonValue.ObjectValue)?.values
	val group = (get("group") as? JsonValue.ObjectValue)?.values
	val student = (get("studentInfo") as? JsonValue.ObjectValue)?.values
	val years = (school?.get("eduYears") as? JsonValue.ArrayValue)?.values.orEmpty()
	val currentYear = years.asSequence()
		.mapNotNull { (it as? JsonValue.ObjectValue)?.values }
		.firstOrNull { (it["isCurrent"] as? JsonValue.BooleanValue)?.value == true }
		?.get("eduYear")?.asText()
	val fullName = listOf("surname", "firstname", "lastname").mapNotNull { get(it)?.asText() }
		.joinToString(" ").ifBlank { null }
	return UserInfo(
		userId = userId,
		fullName = fullName,
		schoolName = school?.get("name")?.asText(),
		region = school?.get("region")?.asText(),
		groupName = group?.get("name")?.asText(),
		schoolId = get("school_id")?.asText(),
		groupId = group?.get("id")?.asText(),
		studentSchoolUuid = student?.get("studentSchoolUuid")?.asText(),
		studentGroupUuid = student?.get("studentGroupUuid")?.asText(),
		eduYear = currentYear,
		role = get("role")?.asText(),
	)
}

private fun Map<String, JsonValue>.toDiaryDay(): DiaryDay {
	val subjects = (get("subjects") as? JsonValue.ArrayValue)?.values.orEmpty().map { value ->
		val item = (value as? JsonValue.ObjectValue)?.values ?: throw IllegalArgumentException("unsupported diary subject")
		DiarySubject(
			scheduleUuid = item["scheduleUuid"]?.asText(),
			label = item["label"]?.asText(),
			type = item["type"]?.asText(),
			timeslot = item["timeslot"]?.asText(),
			cabinet = item["cabinet"]?.asText(),
			theme = item["theme"]?.asText(),
			teacher = item["teacherFio"]?.asText(),
			homework = item["homeworkBody"]?.asText(),
			hasFiles = item["hasFiles"]?.asBoolean() ?: false,
			hasBooks = item["hasBooks"]?.asBoolean() ?: false,
		)
	}
	return DiaryDay(get("day")?.asText(), get("date")?.asText(), get("isHoliday")?.asBoolean() ?: false, subjects)
}

private fun Map<String, JsonValue>.toScheduleDay(): ScheduleDay {
	val lessons = (get("schedule") as? JsonValue.ArrayValue)?.values.orEmpty().map { value ->
		val item = (value as? JsonValue.ObjectValue)?.values ?: throw IllegalArgumentException("unsupported schedule lesson")
		ScheduleLesson(
			uuid = item["uuid"]?.asText(),
			label = item["label"]?.asText(),
			timeslot = item["timeslot"]?.asText(),
			cabinet = item["cabinet"]?.asText(),
			teacher = item["teacherFio"]?.asText(),
			type = item["type"]?.asText(),
			theme = item["theme"]?.asText(),
			homework = item["homeworkBody"]?.asText(),
		)
	}
	return ScheduleDay(get("dateFormat")?.asText() ?: get("date_label")?.asText(), get("isCurrent")?.asBoolean() ?: false, get("isHoliday")?.asBoolean() ?: false, lessons)
}

private fun Map<String, JsonValue>.toGradeSubject(): GradeSubject {
	val schedules = (get("schedules") as? JsonValue.ArrayValue)?.values.orEmpty().map { value ->
		val item = (value as? JsonValue.ObjectValue)?.values ?: throw IllegalArgumentException("unsupported grade schedule")
		GradeSchedule(item["uuid"]?.asText(), item["date"]?.asText(), item["timeStart"]?.asText(), item["markMax"]?.asText()?.toIntOrNull(), item["type"]?.asText())
	}
	return GradeSubject(get("subjectName")?.asText(), get("finalScore")?.asText(), schedules)
}

private fun Map<String, JsonValue>.requiredString(name: String): String =
	when (val value = get(name)) {
		is JsonValue.StringValue -> value.value
		else -> throw IllegalArgumentException("unsupported $name shape")
	}

private fun JsonValue.asText(): String? = when (this) {
	is JsonValue.StringValue -> value
	is JsonValue.NumberValue -> value
	else -> null
}

private fun JsonValue.asBoolean(): Boolean? = (this as? JsonValue.BooleanValue)?.value

private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
		LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
	}

private fun Map<String, JsonValue>.requiredObject(name: String): Map<String, JsonValue> =
	when (val value = get(name)) {
		is JsonValue.ObjectValue -> value.values
		else -> throw IllegalArgumentException("unsupported $name shape")
	}

private fun Map<String, JsonValue>.requiredArray(name: String): List<JsonValue> =
	when (val value = get(name)) {
		is JsonValue.ArrayValue -> value.values
		else -> throw IllegalArgumentException("unsupported $name shape")
	}

private sealed interface JsonValue {
	data class StringValue(val value: String) : JsonValue
	data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
	data class ArrayValue(val values: List<JsonValue>) : JsonValue
	data class NumberValue(val value: String) : JsonValue
	data class BooleanValue(val value: Boolean) : JsonValue
	data object NullValue : JsonValue
}

private class JsonParser(private val source: String) {
	private var position = 0

	fun parseObject(): Map<String, JsonValue> {
		val result = parseObjectValue()
		skipWhitespace()
		if (position != source.length) throw IllegalArgumentException("trailing JSON")
		return result.values
	}

	private fun parseObjectValue(): JsonValue.ObjectValue {
		expect('{')
		val values = linkedMapOf<String, JsonValue>()
		skipWhitespace()
		if (consume('}')) return JsonValue.ObjectValue(values)
		while (true) {
			skipWhitespace()
			val key = parseString()
			skipWhitespace()
			expect(':')
			values[key] = parseValue()
			skipWhitespace()
			if (consume('}')) return JsonValue.ObjectValue(values)
			expect(',')
		}
	}

	private fun parseArrayValue(): JsonValue.ArrayValue {
		expect('[')
		val values = mutableListOf<JsonValue>()
		skipWhitespace()
		if (consume(']')) return JsonValue.ArrayValue(values)
		while (true) {
			values += parseValue()
			skipWhitespace()
			if (consume(']')) return JsonValue.ArrayValue(values)
			expect(',')
		}
	}

	private fun parseValue(): JsonValue {
		skipWhitespace()
		return when {
			peek('{') -> parseObjectValue()
			peek('[') -> parseArrayValue()
			peek('"') -> JsonValue.StringValue(parseString())
			source.startsWith("true", position) -> { position += 4; JsonValue.BooleanValue(true) }
			source.startsWith("false", position) -> { position += 5; JsonValue.BooleanValue(false) }
			source.startsWith("null", position) -> { position += 4; JsonValue.NullValue }
			else -> JsonValue.NumberValue(parseNumber())
		}
	}

	private fun parseString(): String {
		expect('"')
		val result = StringBuilder()
		while (position < source.length) {
			when (val character = source[position++]) {
				'"' -> return result.toString()
				'\\' -> {
					if (position >= source.length) throw IllegalArgumentException("unfinished escape")
					when (val escaped = source[position++]) {
						'"', '\\', '/' -> result.append(escaped)
						'b' -> result.append('\b')
						'f' -> result.append('\u000C')
						'n' -> result.append('\n')
						'r' -> result.append('\r')
						't' -> result.append('\t')
						'u' -> result.append(parseUnicode())
						else -> throw IllegalArgumentException("unsupported escape")
					}
				}
				else -> if (character.code < 0x20) throw IllegalArgumentException("control character") else result.append(character)
			}
		}
		throw IllegalArgumentException("unfinished string")
	}

	private fun parseUnicode(): Char {
		if (position + 4 > source.length) throw IllegalArgumentException("short unicode escape")
		val value = source.substring(position, position + 4).toIntOrNull(16) ?: throw IllegalArgumentException("bad unicode escape")
		position += 4
		return value.toChar()
	}

	private fun parseNumber(): String {
		val start = position
		while (position < source.length && !source[position].isWhitespace() && source[position] !in ",}]") position++
		if (start == position) throw IllegalArgumentException("value expected")
		return source.substring(start, position)
	}

	private fun skipWhitespace() { while (position < source.length && source[position].isWhitespace()) position++ }
	private fun peek(expected: Char): Boolean = position < source.length && source[position] == expected
	private fun consume(expected: Char): Boolean = peek(expected).also { if (it) position++ }
	private fun expect(expected: Char) { if (!consume(expected)) throw IllegalArgumentException("expected $expected") }
}
