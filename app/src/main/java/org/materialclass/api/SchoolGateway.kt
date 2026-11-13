package org.materialclass.api

import java.net.URLEncoder
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.GradesModel
import org.materialclass.data.HomeworkDetailModel
import org.materialclass.data.HomeworksModel
import org.materialclass.data.JournalGradesModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SubjectsModel

/** Keep the transport boundary small so production code cannot invent requests. */
data class HttpRequestData(
	val method: String,
	val url: String,
	val headers: Map<String, String>,
	val body: String,
)

data class HttpResponseData(
	val status: Int,
	val contentType: String,
	val body: String,
	val rawBody: ByteArray? = null,
)

sealed interface HttpResult {
	data class Success(val body: ByteArray) : HttpResult
	data class Failure(val error: AppError) : HttpResult
}

sealed class TransportFailure : Exception() {
	data object Timeout : TransportFailure()
	data object Offline : TransportFailure()
	data object Tls : TransportFailure()
	data object Other : TransportFailure()
}

fun interface HttpsTransport {
	fun execute(request: HttpRequestData): HttpResponseData
}

class SchoolGateway(
	private val transport: HttpsTransport,
	private val log: (String) -> Unit = {},
	private val deviceId: String = java.util.UUID.randomUUID().toString(),
) {
	fun login(credentials: LoginCredentials): GatewayLoginResult {
		if (credentials.login.isBlank() || credentials.password.isBlank()) {
			return GatewayLoginResult.Failure(AppError.InvalidCredentials)
		}
		val request = HttpRequestData(
			method = "POST",
			url = LOGIN_URL,
				headers = mapOf(
					"User-Agent" to USER_AGENT,
					"Accept" to "application/json",
				"Content-Type" to "application/json",
				"X-Localization" to "ru",
				"X-Mathrix" to deviceId,
				"Origin" to "https://www.bilimclass.kz",
				"Referer" to "https://www.bilimclass.kz/",
			),
			body = "{\"login\":${jsonString(credentials.login)},\"password\":${jsonString(credentials.password)}}",
		)
		log("login request POST $LOGIN_URL")
		return try {
			val response = transport.execute(request)
			when {
				response.status == 401 -> GatewayLoginResult.Failure(AppError.InvalidCredentials)
				response.status == 403 -> GatewayLoginResult.Failure(AppError.DisallowedRole)
				response.status !in 200..299 -> GatewayLoginResult.Failure(AppError.HttpStatus(response.status))
				!response.contentType.lowercase().startsWith("application/json") -> GatewayLoginResult.Failure(AppError.InvalidResponse)
				else -> ContractMapper.mapLoginResponse(response.body)
			}
		} catch (_: TransportFailure.Timeout) {
			GatewayLoginResult.Failure(AppError.Timeout)
		} catch (_: TransportFailure.Offline) {
			GatewayLoginResult.Failure(AppError.Offline)
		} catch (_: TransportFailure.Tls) {
			GatewayLoginResult.Failure(AppError.TlsFailure)
		} catch (_: Exception) {
			GatewayLoginResult.Failure(AppError.Network)
		}
	}

	fun refresh(refreshToken: String): GatewayRefreshResult {
		if (refreshToken.isBlank()) return GatewayRefreshResult.Failure(AppError.LoginRequired)
		val request = HttpRequestData(
			method = "POST",
			url = REFRESH_URL,
			headers = mapOf(
				"User-Agent" to USER_AGENT,
				"Accept" to "application/json",
				"Content-Type" to "application/json",
				"X-Localization" to "ru",
				"X-Mathrix" to deviceId,
				"Origin" to "https://www.bilimclass.kz",
				"Referer" to "https://www.bilimclass.kz/",
			),
			body = "{\"refreshToken\":${jsonString(refreshToken)}}",
		)
		return try {
			val response = transport.execute(request)
			when {
				response.status in setOf(401, 403, 404, 426) -> GatewayRefreshResult.Failure(AppError.LoginRequired)
				response.status !in 200..299 -> GatewayRefreshResult.Failure(AppError.HttpStatus(response.status))
				!response.contentType.lowercase().startsWith("application/json") -> GatewayRefreshResult.Failure(AppError.InvalidResponse)
				else -> ContractMapper.mapRefreshResponse(response.body)
			}
		} catch (_: TransportFailure.Timeout) {
			GatewayRefreshResult.Failure(AppError.Timeout)
		} catch (_: TransportFailure.Offline) {
			GatewayRefreshResult.Failure(AppError.Offline)
		} catch (_: TransportFailure.Tls) {
			GatewayRefreshResult.Failure(AppError.TlsFailure)
		} catch (_: Exception) {
			GatewayRefreshResult.Failure(AppError.Network)
		}
	}

	fun logout(context: ReadContext): GatewayReadResult<Unit> =
		read(context, LOGOUT_PATH, { GatewayReadResult.Success(Unit) }, mapOf("soft" to "0"))

	fun diaryWeeks(context: ReadContext): GatewayReadResult<DiaryWeeksModel> =
		read(context, DIARY_WEEKS_PATH, ContractMapper::mapDiaryWeeks)

	fun diary(context: ReadContext): GatewayReadResult<DiaryModel> =
		read(context, DIARY_PATH, ContractMapper::mapDiary)

	fun diaryPeriods(context: ReadContext): GatewayReadResult<PeriodsModel> =
		read(context, DIARY_PERIODS_PATH, ContractMapper::mapDiaryPeriods)

	fun periods(context: ReadContext): GatewayReadResult<PeriodsModel> = diaryPeriods(context)

	fun subjects(context: ReadContext): GatewayReadResult<SubjectsModel> =
		read(context, SUBJECTS_PATH, ContractMapper::mapSubjects)

	fun grades(context: ReadContext): GatewayReadResult<GradesModel> =
		read(context, YEAR_GRADES_PATH, ContractMapper::mapGrades)

	fun schedule(context: ReadContext): GatewayReadResult<ScheduleModel> =
		read(context, SCHEDULE_PATH, ContractMapper::mapSchedule)

	fun homeworks(context: ReadContext): GatewayReadResult<HomeworksModel> =
		read(context, HOMEWORKS_PATH, ContractMapper::mapHomeworks)

	fun homeworkDetail(context: ReadContext): GatewayReadResult<HomeworkDetailModel> =
		read(context, HOMEWORK_DETAIL_PATH, ContractMapper::mapHomeworkDetail)

	fun journalGrades(context: ReadContext): GatewayReadResult<JournalGradesModel> =
		readJournal(context, ContractMapper::mapJournalGrades)

	fun downloadSubjectsExcel(context: ReadContext): HttpResult =
		readBinary(context, SUBJECTS_EXCEL_PATH)

	fun system(context: ReadContext, path: String): GatewayReadResult<org.materialclass.data.SystemDataModel> =
		read(context, path, ContractMapper::mapSystem)

	fun educationYears(context: ReadContext): GatewayReadResult<org.materialclass.data.SystemDataModel> =
		system(context, SYSTEM_EDUCATION_YEARS_PATH)

	fun systemSettings(context: ReadContext): GatewayReadResult<org.materialclass.data.SystemDataModel> =
		system(context, SYSTEM_SETTINGS_PATH)

	fun relatedChildren(context: ReadContext): GatewayReadResult<org.materialclass.data.SystemDataModel> =
		system(context, SYSTEM_RELATED_CHILDREN_PATH)

	private fun <T> read(
		context: ReadContext,
		path: String,
		mapper: (String) -> GatewayReadResult<T>,
		extraParameters: Map<String, String> = emptyMap(),
	): GatewayReadResult<T> {
		if (context.accessToken.isBlank()) return GatewayReadResult.Failure(AppError.Unauthorized)
		val request = HttpRequestData(
			method = "GET",
			url = buildUrl(path, context.queryParameters + extraParameters),
			headers = buildMap {
				put("User-Agent", USER_AGENT)
				put("Accept", "application/json")
				put("Authorization", "Bearer ${context.accessToken}")
				put("X-Localization", "ru")
				context.queryParameters["schoolId"]?.let { put("x-school-id", it) }
			},
			body = "",
		)
		log("read request GET $path")
		return try {
			val response = transport.execute(request)
			when {
				response.status == 401 -> GatewayReadResult.Failure(AppError.Unauthorized)
				response.status == 403 -> GatewayReadResult.Failure(AppError.Forbidden)
				response.status !in 200..299 -> GatewayReadResult.Failure(
					if (response.status >= 500) AppError.Server else AppError.HttpStatus(response.status),
				)
				!response.contentType.lowercase().startsWith("application/json") -> GatewayReadResult.Failure(AppError.InvalidResponse)
				else -> mapper(response.body)
			}
		} catch (_: TransportFailure.Timeout) {
			GatewayReadResult.Failure(AppError.Timeout)
		} catch (_: TransportFailure.Offline) {
			GatewayReadResult.Failure(AppError.Offline)
		} catch (_: TransportFailure.Tls) {
			GatewayReadResult.Failure(AppError.TlsFailure)
		} catch (_: Exception) {
			GatewayReadResult.Failure(AppError.Network)
		}
	}

	private fun <T> readJournal(
		context: ReadContext,
		mapper: (String) -> GatewayReadResult<T>,
	): GatewayReadResult<T> {
		if (context.accessToken.isBlank()) return GatewayReadResult.Failure(AppError.Unauthorized)
		val request = HttpRequestData(
			method = "GET",
			url = buildUrl(JOURNAL_PATH, context.queryParameters, JOURNAL_BASE_URL),
			headers = mapOf(
				"User-Agent" to USER_AGENT,
				"Accept" to "application/json",
				"Authorization" to "Bearer ${context.secondaryToken ?: context.accessToken}",
				"external" to "1",
				"Origin" to "https://www.bilimclass.kz",
				"Referer" to "https://www.bilimclass.kz/",
			),
			body = "",
		)
		return try {
			val response = transport.execute(request)
			when {
				response.status == 401 -> GatewayReadResult.Failure(AppError.Unauthorized)
				response.status !in 200..299 -> GatewayReadResult.Failure(AppError.HttpStatus(response.status))
				!response.contentType.lowercase().startsWith("application/json") -> GatewayReadResult.Failure(AppError.InvalidResponse)
				else -> mapper(response.body)
			}
		} catch (_: TransportFailure.Timeout) {
			GatewayReadResult.Failure(AppError.Timeout)
		} catch (_: TransportFailure.Offline) {
			GatewayReadResult.Failure(AppError.Offline)
		} catch (_: Exception) {
			GatewayReadResult.Failure(AppError.Network)
		}
	}

	private fun readBinary(context: ReadContext, path: String): HttpResult {
		if (context.accessToken.isBlank()) return HttpResult.Failure(AppError.Unauthorized)
		val request = HttpRequestData(
			method = "GET",
			url = buildUrl(path, context.queryParameters),
			headers = mapOf(
				"Accept" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
				"Authorization" to "Bearer ${context.accessToken}",
			),
			body = "",
		)
		return try {
			val response = transport.execute(request)
			when {
				response.status == 401 -> HttpResult.Failure(AppError.Unauthorized)
				response.status !in 200..299 -> HttpResult.Failure(AppError.HttpStatus(response.status))
				!response.contentType.lowercase().let { it.contains("spreadsheet") || it.contains("excel") } -> HttpResult.Failure(AppError.InvalidResponse)
				else -> HttpResult.Success(response.rawBody ?: response.body.toByteArray(Charsets.ISO_8859_1))
			}
		} catch (_: TransportFailure.Timeout) {
			HttpResult.Failure(AppError.Timeout)
		} catch (_: TransportFailure.Offline) {
			HttpResult.Failure(AppError.Offline)
		} catch (_: Exception) {
			HttpResult.Failure(AppError.Network)
		}
	}

	private companion object {
		const val LOGIN_URL = "https://api.bilimclass.kz/api/v2/os/login"
		const val REFRESH_URL = "https://api.bilimclass.kz/api/v2/os/refresh_token"
		const val LOGOUT_PATH = "/api/logout"
		const val USER_AGENT = "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
		const val DIARY_WEEKS_PATH = "/api/v4/os/clientoffice/diary/weeks"
		const val DIARY_PATH = "/api/v4/os/clientoffice/diary"
		const val DIARY_PERIODS_PATH = "/api/v4/os/clientoffice/diary/periods"
		const val SUBJECTS_PATH = "/api/v4/os/clientoffice/diary/subjects"
		const val YEAR_GRADES_PATH = "/api/v4/os/clientoffice/diary/year"
		const val SCHEDULE_PATH = "/api/v4/os/clientoffice/schedule"
		const val HOMEWORKS_PATH = "/api/v4/os/clientoffice/homeworks/monthly/list"
		const val HOMEWORK_DETAIL_PATH = "/api/v4/os/clientoffice/homeworks/simple-homework/info"
		const val JOURNAL_BASE_URL = "https://journal-service.bilimclass.kz"
		const val JOURNAL_PATH = "/diary/quarter"
		const val SUBJECTS_EXCEL_PATH = "/api/v4/os/clientoffice/diary/subjects/excel"
		const val SYSTEM_EDUCATION_YEARS_PATH = "/api/v4/os/system/education-years"
		const val SYSTEM_SETTINGS_PATH = "/api/v4/os/system/settings"
		const val SYSTEM_RELATED_CHILDREN_PATH = "/api/v4/os/system/related-children"
		const val BASE_URL = "https://api.bilimclass.kz"

		fun buildUrl(path: String, parameters: Map<String, String>, baseUrl: String = BASE_URL): String {
			if (parameters.isEmpty()) return "$baseUrl$path"
			val query = parameters.entries.joinToString("&") { (key, value) ->
				"${encode(key)}=${encode(value)}"
			}
			return "$baseUrl$path?$query"
		}

		fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

		fun jsonString(value: String): String = buildString {
			append('"')
			value.forEach { character ->
				when (character) {
					'\\' -> append("\\\\")
					'"' -> append("\\\"")
					'\n' -> append("\\n")
					'\r' -> append("\\r")
					'\t' -> append("\\t")
					else -> append(character)
				}
			}
			append('"')
		}
	}
}

class UrlConnectionTransport : HttpsTransport {
	override fun execute(request: HttpRequestData): HttpResponseData {
		val connection = java.net.URI(request.url).toURL().openConnection() as java.net.HttpURLConnection
		try {
			connection.requestMethod = request.method
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.useCaches = false
			request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
			if (request.body.isNotEmpty()) {
				connection.doOutput = true
				connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
			}
			val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
			val rawBody = stream?.use { it.readBytes() } ?: byteArrayOf()
			return HttpResponseData(
				connection.responseCode,
				connection.contentType.orEmpty(),
				rawBody.toString(Charsets.UTF_8),
				rawBody,
			)
		} finally {
			connection.disconnect()
		}
	}

	private companion object {
		const val TIMEOUT_MS = 15_000
	}
}

data class ReadContext(
	val accessToken: String,
	val queryParameters: Map<String, String> = emptyMap(),
	val secondaryToken: String? = null,
) {
	override fun toString(): String = "ReadContext(queryParameterNames=${queryParameters.keys})"
}

sealed interface GatewayReadResult<out T> {
	data class Success<T>(val value: T) : GatewayReadResult<T>
	data object Empty : GatewayReadResult<Nothing>
	data class Failure<T>(val error: AppError) : GatewayReadResult<T>
}

data class LoginCredentials(
	val login: String,
	val password: String,
)

sealed interface GatewayLoginResult {
	data class Success(val response: LoginResponse) : GatewayLoginResult
	data class Failure(val error: AppError) : GatewayLoginResult
}
