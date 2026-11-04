package org.materialclass.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.materialclass.session.RequestLease
import org.materialclass.session.SessionCoordinator

interface SchoolGateway {
	fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession>
	fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary>
	fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String>
	fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule>

}

interface DiaryWeeksGateway {
	fun diaryWeeks(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<DiaryWeeks>
}

interface AnnualGradesGateway {
	fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades>
}

class AnnualGrades(val rows: List<AnnualGradeRow>)
class AnnualGradeRow(val subjectName: String, val quarters: List<String?>, val yearScore: String?, val finalScore: String?)

class VerifiedDiarySession private constructor(internal val remote: UnverifiedSession, internal val account: String) {
	override fun toString(): String = "VerifiedDiarySession([redacted])"

	internal fun permits(context: DiaryContext): Boolean {
		val school = remote.context ?: return false
		return school.schoolId == context.schoolId && school.groupId == context.groupId && school.eduYear == context.eduYear
	}

	companion object {
		internal fun compare(remote: UnverifiedSession, profile: JSONObject): VerifiedDiarySession? {
			val id = profile.opt("userId").integerValue()?.takeIf { it > 0 }?.toString() ?: return null
			if (remote.token.isEmpty() || remote.identityCandidate != id || remote.role != "student" || profile.opt("role") != remote.role) return null
			val login = remote.context ?: return null
			val actual = schoolContext(profile) ?: return null
			if (login.schoolId != actual.schoolId || login.groupId != actual.groupId || login.eduYear != actual.eduYear) return null
			return VerifiedDiarySession(remote, id)
		}
	}
}

class VerifiedScheduleSession private constructor(internal val remote: UnverifiedSession, internal val account: String) {
	override fun toString(): String = "VerifiedScheduleSession([redacted])"

	companion object {
		internal fun compare(remote: UnverifiedSession, profile: JSONObject): VerifiedScheduleSession? {
			val id = profile.opt("userId").integerValue()?.toString() ?: return null
			if (remote.token.isEmpty() || remote.identityCandidate != id || remote.role != "student" || profile.opt("role") != remote.role) return null
			val login = remote.context ?: return null
			val actual = schoolContext(profile) ?: return null
			val uuid = login.studentSchoolUuid ?: return null
			if (!uuid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return null
			if (login.schoolId != actual.schoolId || login.groupId != actual.groupId || login.eduYear != actual.eduYear || uuid != actual.studentSchoolUuid) return null
			return VerifiedScheduleSession(remote, id)
		}
	}
}

class StudentProfile(val surname: String?, val firstname: String?, val school: String?, val group: String?) {
	override fun toString(): String = "StudentProfile([redacted])"
}

class Schedule(val days: List<ScheduleDay>)
class ScheduleDay(val date: Long, val dateFormat: String, val isCurrent: Boolean, val isHoliday: Boolean, val lessons: List<ScheduleLesson>)
class ScheduleLesson(val type: String, val cabinetId: Long, val themeLabel: String)


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

class SchoolContext(val schoolId: Long, val eduYear: Int, val groupId: Long, internal val studentSchoolUuid: String? = null) {
	fun diary(date: String): DiaryContext = DiaryContext(schoolId, eduYear, groupId, date)
	override fun toString(): String = "SchoolContext([redacted])"
}

class DiaryContext(val schoolId: Long, val eduYear: Int, val groupId: Long, val date: String) {
	override fun toString(): String = "DiaryContext([redacted])"
}

data class DiaryWeekOption(val label: String, val value: String)
data class DiaryWeeks(val currentWeek: String, val options: List<DiaryWeekOption>)

class Diary(val date: String, val days: List<DiaryDay>)
class DiaryDay(val day: String, val date: String, val subjects: List<DiarySubject>)
class DiarySubject(val label: String, val type: String, val scheduleUuid: String, val homeworkType: String, val homeworkBody: String?, val homeworkUuid: String?, val hasBooks: Boolean, val hasFiles: Boolean)

internal class Request(val url: URL, val method: String, val headers: Map<String, String>, val body: ByteArray?, val lease: RequestLease)
internal class Response(val status: Int, val body: String)
internal fun interface Exchange {
	fun execute(request: Request): Response
}

class NativeGateway internal constructor(private val exchange: Exchange) : SchoolGateway, DiaryWeeksGateway, AnnualGradesGateway {
	constructor() : this(HttpsExchange())

	private val deviceId = UUID.randomUUID().toString()

	override fun annualGrades(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<AnnualGrades> {
		return try {
			val remote = session.remote
			val context = remote.context ?: return GatewayResult.Failure(AppError.UNAVAILABLE)
			if (!SessionCoordinator.permits(lease, session.account) || session.account != remote.identityCandidate || remote.role != "student" || remote.token.isEmpty() || context.schoolId <= 0 || context.groupId <= 0 || context.eduYear <= 0) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val query = "schoolId=${context.schoolId}&eduYear=${context.eduYear}"
			val headers = mapOf("Authorization" to "Bearer ${remote.token}", "x-school-id" to context.schoolId.toString())
			val response = exchange.execute(request("/api/v4/os/clientoffice/diary/year?$query", "GET", headers, null, lease))
			if (lease.isCancelled()) return GatewayResult.Failure(AppError.UNAVAILABLE)
			if (response.status == 401) return GatewayResult.Failure(AppError.SESSION_EXPIRED)
			if (response.status == 403) return GatewayResult.Failure(AppError.FORBIDDEN)
			if (response.status != 200) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val array = JSONObject(response.body).getJSONObject("data").getJSONArray("rows")
			require(array.length() <= 10000)
			val rows = array.mapObjects { row ->
				val name = row.strictString("subjectName")
				require(name.length <= 1024)
				val attestations = row.optionalObject("attestations")
				val scores = row.optionalObject("yearScores")
				AnnualGradeRow(name, (1..4).map { attestations?.optionalScore("quarter$it") }, scores?.optionalScore("yearScore"), scores?.optionalScore("finalScore"))
			}
			if (lease.isCancelled()) GatewayResult.Failure(AppError.UNAVAILABLE) else GatewayResult.Success(AnnualGrades(rows))
		} catch (_: SocketTimeoutException) {
			GatewayResult.Failure(AppError.TIMEOUT)
		} catch (_: java.io.IOException) {
			GatewayResult.Failure(AppError.OFFLINE)
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
		}
	}

	override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> = loginRequest(login, password, lease)

	private fun loginRequest(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
		return try {
			val body = JSONObject().put("login", login).put("password", String(password)).toString().toByteArray(Charsets.UTF_8)
			val response = try {
				exchange.execute(request("/api/v2/os/login", "POST", emptyMap(), body, lease))
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

	override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = when (val result = diaryWeeks(session, lease)) {
		is GatewayResult.Success -> GatewayResult.Success(result.value.currentWeek)
		is GatewayResult.Failure -> result
	}

	override fun diaryWeeks(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<DiaryWeeks> {
		return try {
			val remote = session.remote
			val context = remote.context ?: return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			if (!SessionCoordinator.permits(lease, session.account) || session.account != remote.identityCandidate || remote.token.isEmpty() || remote.role != "student" || context.schoolId <= 0 || context.groupId <= 0 || context.eduYear <= 0) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val query = "schoolId=${context.schoolId}&eduYear=${context.eduYear}"
			val headers = mapOf("Authorization" to "Bearer ${remote.token}", "x-school-id" to context.schoolId.toString())
			val response = exchange.execute(request("/api/v4/os/clientoffice/diary/weeks?$query", "GET", headers, null, lease))
			if (lease.isCancelled() || response.status != 200) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val data = JSONObject(response.body).getJSONObject("data")
			val week = data.strictString("week")
			if (calendarDate(week) == null) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val options = mutableListOf<DiaryWeekOption>()
			val seen = mutableSetOf<String>()
			val array = data.opt("weeks") as? JSONArray
			if (array != null && array.length() <= 256) {
				for (index in 0 until array.length()) {
					val item = array.opt(index) as? JSONObject ?: continue
					val label = item.opt("label") as? String ?: continue
					val value = item.opt("value") as? String ?: continue
					if (label.isBlank() || label.length > 256 || calendarDate(value) == null || !seen.add(value)) continue
					options.add(DiaryWeekOption(label, value))
				}
			}
			GatewayResult.Success(DiaryWeeks(week, options))
		} catch (_: SocketTimeoutException) {
			GatewayResult.Failure(AppError.TIMEOUT)
		} catch (_: java.io.IOException) {
			GatewayResult.Failure(AppError.OFFLINE)
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
		}
	}

	override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = diaryRequest(session, context, lease)

	private fun diaryRequest(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> {
		return try {
			val remote = session.remote
			if (session.account != remote.identityCandidate || remote.token.isEmpty() || remote.role != "student" || !session.permits(context) || context.schoolId <= 0 || context.groupId <= 0 || context.eduYear <= 0 || !context.date.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val query = listOf("schoolId" to context.schoolId.toString(), "eduYear" to context.eduYear.toString(), "date" to context.date, "groupId" to context.groupId.toString()).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
			val headers = mapOf("Authorization" to "Bearer ${remote.token}", "x-school-id" to context.schoolId.toString())
			val response = exchange.execute(request("/api/v4/os/clientoffice/diary?$query", "GET", headers, null, lease))
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

	override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = scheduleRequest(session, date, lease)

	private fun scheduleRequest(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> {
		return try {
			val remote = session.remote
			val context = remote.context ?: return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val uuid = context.studentSchoolUuid ?: return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			if (session.account != remote.identityCandidate || remote.role != "student" || remote.token.isEmpty() || context.schoolId <= 0 || context.eduYear <= 0 || context.groupId <= 0 || !uuid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) || !date.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val requested = calendarDate(date) ?: return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val query = listOf("schoolId" to context.schoolId.toString(), "eduYear" to context.eduYear.toString(), "studentSchoolUuid" to uuid, "date" to date, "groupId" to context.groupId.toString()).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
			val response = exchange.execute(request("/api/v4/os/clientoffice/schedule?$query", "GET", mapOf("Authorization" to "Bearer ${remote.token}", "x-school-id" to context.schoolId.toString()), null, lease))
			if (response.status != 200) return GatewayResult.Failure(AppError.UNAVAILABLE)
			val data = JSONObject(response.body).getJSONObject("data")
			val weeks = data.getJSONObject("weeks")
			weeks.strictString("next"); weeks.strictString("prev")
			weeks.get("next_time").integerValue() ?: throw IllegalArgumentException()
			weeks.get("prev_time").integerValue() ?: throw IllegalArgumentException()
			val days = data.getJSONArray("days").mapObjects { day ->
				val timestamp = day.get("date").integerValue() ?: throw IllegalArgumentException()
				ScheduleDay(timestamp, day.strictString("dateFormat"), day.strictBoolean("isCurrent"), day.strictBoolean("isHoliday"), day.getJSONArray("schedule").mapObjects { lesson ->
					val cabinet = lesson.getJSONObject("cabinet").get("id").integerValue() ?: throw IllegalArgumentException()
					ScheduleLesson(lesson.strictString("type"), cabinet, lesson.getJSONObject("theme").strictString("label"))
				})
			}
			if (days.size != 7) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			val dates = days.map { calendarDate(it.dateFormat) ?: throw IllegalArgumentException() }
			if (requested !in dates || dates.zipWithNext().any { (first, second) -> second - first != 86_400_000L }) return GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
			GatewayResult.Success(Schedule(days))
		} catch (_: SocketTimeoutException) {
			GatewayResult.Failure(AppError.TIMEOUT)
		} catch (_: java.io.IOException) {
			GatewayResult.Failure(AppError.OFFLINE)
		} catch (_: Exception) {
			GatewayResult.Failure(AppError.INCOMPATIBLE_RESPONSE)
		}
	}

	private fun request(path: String, method: String, extra: Map<String, String>, body: ByteArray?, lease: RequestLease): Request {
		val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json", "X-Localization" to "ru", "X-Mathrix" to deviceId, "Origin" to "https://www.bilimclass.kz", "Referer" to "https://www.bilimclass.kz/", "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36") + extra
		return Request(URL("https://api.bilimclass.kz$path"), method, headers, body, lease)
	}
}

// Treat cancellation as best effort once I/O is admitted; do not assume bytes cannot leave after revocation.
internal class HttpsExchange(private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }, private val afterRegistration: () -> Unit = {}) : Exchange {
	override fun execute(request: Request): Response {
		val url = request.url
		require(url.protocol == "https" && url.host == "api.bilimclass.kz" && (url.port == -1 || url.port == 443) && url.userInfo == null)
		if (request.lease.isCancelled()) throw java.io.IOException("Request cancelled")
		val connection = open(url)
		try {
			if (!request.lease.register { connection.disconnect() }) throw java.io.IOException("Request cancelled")
			afterRegistration()
			if (request.lease.isCancelled()) throw java.io.IOException("Request cancelled")
			connection.instanceFollowRedirects = false
			connection.connectTimeout = 15000
			connection.readTimeout = 15000
			connection.requestMethod = request.method
			connection.useCaches = false
			request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
			request.body?.let {
				connection.doOutput = true
				if (request.lease.isCancelled()) throw java.io.IOException("Request cancelled")
				connection.outputStream.use { output ->
					if (request.lease.isCancelled()) throw java.io.IOException("Request cancelled")
					output.write(it)
				}
			}
			if (request.lease.isCancelled()) throw java.io.IOException("Request cancelled")
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
			request.lease.unregister()
			connection.disconnect()
		}
	}
}

private fun calendarDate(value: String): Long? {
	if (!value.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))) return null
	val format = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply {
		isLenient = false
		timeZone = TimeZone.getTimeZone("UTC")
	}
	val position = ParsePosition(0)
	val parsed = format.parse(value, position) ?: return null
	return parsed.time.takeIf { position.index == value.length && format.format(parsed) == value }
}

internal fun schoolContext(user: JSONObject): SchoolContext? {
	val schoolId = user.opt("school_id").integerValue() ?: return null
	val groupId = user.optJSONObject("group")?.opt("id").integerValue() ?: return null
	val years = user.optJSONObject("school")?.optJSONArray("eduYears") ?: return null
	val entries = (0 until years.length()).map { years.optJSONObject(it) ?: return null }
	if (entries.any { it.opt("isCurrent") !is Boolean }) return null
	val current = entries.filter { it.opt("isCurrent") == true }
	if (current.size != 1) return null
	val year = current.single().opt("eduYear").integerValue() ?: return null
	if (schoolId <= 0 || groupId <= 0 || year <= 0 || year > Int.MAX_VALUE) return null
	val studentInfo = user.opt("studentInfo")
	val uuid = when (studentInfo) {
		null, JSONObject.NULL -> null
		is JSONObject -> when (val value = studentInfo.opt("studentSchoolUuid")) {
			null, JSONObject.NULL -> null
			is String -> value
			else -> return null
		}
		else -> return null
	}
	return SchoolContext(schoolId, year.toInt(), groupId, uuid)
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
private fun JSONObject.optionalObject(key: String): JSONObject? = when (val value = opt(key)) {
	null, JSONObject.NULL -> null
	is JSONObject -> value
	else -> throw IllegalArgumentException()
}
private fun JSONObject.optionalScore(key: String): String? = when (val value = opt(key)) {
	null, JSONObject.NULL -> null
	is String -> value.also { require(it.length <= 1024) }
	else -> throw IllegalArgumentException()
}
