package org.materialclass.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

interface SchoolGateway {
	fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession>
	fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary>
}

sealed class GatewayResult<out T> {
	class Success<T>(val value: T) : GatewayResult<T>()
	class Failure(val error: AppError) : GatewayResult<Nothing>()
}

class UnverifiedSession internal constructor(
	internal val token: String,
	val identityCandidate: String?,
	val role: String,
	val context: SchoolContext? = null,
) {
	val identityVerified: Boolean = false
	override fun toString(): String = "UnverifiedSession([redacted])"
}

class SchoolContext(val schoolId: Long, val eduYear: Int, val groupId: Long) {
	fun diary(date: String): DiaryContext = DiaryContext(schoolId, eduYear, groupId, date)
	override fun toString(): String = "SchoolContext([redacted])"
}

class DiaryContext(val schoolId: Long, val eduYear: Int, val groupId: Long, val date: String) {
	override fun toString(): String = "DiaryContext([redacted])"
}

class Diary(val date: String, val days: List<DiaryDay>)
class DiaryDay(val day: String, val date: String, val subjects: List<DiarySubject>)
class DiarySubject(val label: String, val type: String, val scheduleUuid: String, val homeworkType: String, val homeworkBody: String?, val homeworkUuid: String?, val hasBooks: Boolean, val hasFiles: Boolean)

internal class Request(val url: URL, val method: String, val headers: Map<String, String>, val body: ByteArray?)
internal class Response(val status: Int, val body: String)
internal fun interface Exchange {
	fun execute(request: Request): Response
}

class NativeGateway internal constructor(private val exchange: Exchange) : SchoolGateway {
	constructor() : this(HttpsExchange())

	private val deviceId = UUID.randomUUID().toString()

	override fun login(login: String, password: CharArray): GatewayResult<UnverifiedSession> {
		return try {
			val body = JSONObject().put("login", login).put("password", String(password)).toString().toByteArray(Charsets.UTF_8)
			val response = try {
				exchange.execute(request("/api/v2/os/login", "POST", emptyMap(), body))
			} finally {
				body.fill(0)
			}
			if (response.status != 200) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val root = JSONObject(response.body)
			if (root.get("success") != true) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val token = root.strictString("access_token")
			val user = root.getJSONObject("user_info")
			val role = user.strictString("role")
			if (role != "student") return GatewayResult.Failure(AppError.FORBIDDEN)
			val candidate = user.opt("userId")
			val id = candidate.integerValue()?.takeIf { it > 0 }?.toString() ?: return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			GatewayResult.Success(UnverifiedSession(token, id, role, schoolContext(user)))
		} catch (_: SocketTimeoutException) {
			GatewayResult.Failure(AppError.TIMEOUT)
		} catch (_: java.io.IOException) {
			GatewayResult.Failure(AppError.OFFLINE)
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
		} finally {
			password.fill('\u0000')
		}
	}

	override fun diary(session: UnverifiedSession, context: DiaryContext): GatewayResult<Diary> {
		return try {
			if (session.role != "student" || context.schoolId <= 0 || context.groupId <= 0 || context.eduYear <= 0 || !context.date.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val query = listOf("schoolId" to context.schoolId.toString(), "eduYear" to context.eduYear.toString(), "date" to context.date, "groupId" to context.groupId.toString()).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
			val headers = mapOf("Authorization" to "Bearer ${session.token}", "x-school-id" to context.schoolId.toString())
			val response = exchange.execute(request("/api/v4/os/clientoffice/diary?$query", "GET", headers, null))
			if (response.status != 200) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val data = JSONObject(response.body).getJSONObject("data")
			val days = data.getJSONArray("days").mapObjects { day ->
				DiaryDay(day.strictString("day"), day.strictString("date"), day.getJSONArray("subjects").mapObjects { subject ->
					DiarySubject(subject.strictString("label"), subject.strictString("type"), subject.strictString("scheduleUuid"), subject.strictString("homeworkType"), subject.nullableString("homeworkBody"), subject.nullableString("homeworkUuid"), subject.strictBoolean("hasBooks"), subject.strictBoolean("hasFiles"))
				})
			}
			GatewayResult.Success(Diary(data.strictString("date"), days))
		} catch (_: SocketTimeoutException) {
			GatewayResult.Failure(AppError.TIMEOUT)
		} catch (_: java.io.IOException) {
			GatewayResult.Failure(AppError.OFFLINE)
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
		}
	}

	private fun request(path: String, method: String, extra: Map<String, String>, body: ByteArray?): Request {
		val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json", "X-Localization" to "ru", "X-Mathrix" to deviceId, "Origin" to "https://www.bilimclass.kz", "Referer" to "https://www.bilimclass.kz/", "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36") + extra
		return Request(URL("https://api.bilimclass.kz$path"), method, headers, body)
	}
}

internal class HttpsExchange : Exchange {
	override fun execute(request: Request): Response {
		val url = request.url
		require(url.protocol == "https" && url.host == "api.bilimclass.kz" && (url.port == -1 || url.port == 443) && url.userInfo == null)
		val connection = url.openConnection() as HttpsURLConnection
		try {
			connection.instanceFollowRedirects = false
			connection.connectTimeout = 15000
			connection.readTimeout = 15000
			connection.requestMethod = request.method
			connection.useCaches = false
			request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
			request.body?.let { connection.doOutput = true; connection.outputStream.use { output -> output.write(it) } }
			val status = connection.responseCode
			if (status != 200) return Response(status, "")
			val bytes = connection.inputStream.use { input ->
				val output = java.io.ByteArrayOutputStream()
				val buffer = ByteArray(8192)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					if (output.size() + count > 2_000_000) throw java.io.IOException()
					output.write(buffer, 0, count)
				}
				output.toByteArray()
			}
			return Response(status, String(bytes, Charsets.UTF_8))
		} finally {
			connection.disconnect()
		}
	}
}

private fun schoolContext(user: JSONObject): SchoolContext? {
	val schoolId = user.opt("school_id").integerValue() ?: return null
	val groupId = user.optJSONObject("group")?.opt("id").integerValue() ?: return null
	val years = user.optJSONObject("school")?.optJSONArray("eduYears") ?: return null
	val current = (0 until years.length()).mapNotNull { years.optJSONObject(it) }.filter { it.opt("isCurrent") == true }
	if (current.size != 1) return null
	val year = current.single().opt("eduYear").integerValue() ?: return null
	if (schoolId <= 0 || groupId <= 0 || year <= 0 || year > Int.MAX_VALUE) return null
	return SchoolContext(schoolId, year.toInt(), groupId)
}

private fun Any?.integerValue(): Long? = when (this) {
	is Int -> toLong()
	is Long -> this
	else -> null
}

private fun JSONObject.strictString(key: String): String = (get(key) as? String)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException()
private fun JSONObject.nullableString(key: String): String? = when (val value = get(key)) {
	JSONObject.NULL -> null
	is String -> value
	else -> throw IllegalArgumentException()
}
private fun JSONObject.strictBoolean(key: String): Boolean = get(key) as? Boolean ?: throw IllegalArgumentException()
private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = (0 until length()).map { transform(getJSONObject(it)) }
