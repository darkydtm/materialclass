package org.materialclass.api

import java.time.Instant
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SubjectsModel

/** Map only fields observed in the confirmed responses. */
object ContractMapper {
	fun mapLoginResponse(body: String): GatewayLoginResult = try {
		val root = JsonParser(body).parseObject()
		val accessToken = root.requiredString("access_token")
		val refreshToken = root.requiredString("refresh_token")
		val expiresAt = Instant.parse(root.requiredString("exp_time"))
		val userInfo = root.requiredObject("user_info")
		val userId = userInfo.requiredString("userId")
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
		val days = JsonParser(body).parseObject().requiredObject("data").requiredArray("days")
		if (days.any { it !is JsonValue.ObjectValue }) throw IllegalArgumentException("unsupported day shape")
		if (days.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(DiaryModel(days.size))
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
		if (days.isEmpty()) GatewayReadResult.Empty else GatewayReadResult.Success(ScheduleModel(days.size, scheduledDays))
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
) {
	override fun toString(): String = "UserInfo(userId=<redacted>)"
}

private fun Map<String, JsonValue>.requiredString(name: String): String =
	when (val value = get(name)) {
		is JsonValue.StringValue -> value.value
		else -> throw IllegalArgumentException("unsupported $name shape")
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
	data object BooleanValue : JsonValue
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
			source.startsWith("true", position) -> { position += 4; JsonValue.BooleanValue }
			source.startsWith("false", position) -> { position += 5; JsonValue.BooleanValue }
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
