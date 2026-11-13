package org.materialclass.api

import java.net.URLEncoder
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.GradesModel
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
)

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

	private fun <T> read(
		context: ReadContext,
		path: String,
		mapper: (String) -> GatewayReadResult<T>,
	): GatewayReadResult<T> {
		if (context.accessToken.isBlank()) return GatewayReadResult.Failure(AppError.Unauthorized)
		val request = HttpRequestData(
			method = "GET",
			url = buildUrl(path, context.queryParameters),
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

	private companion object {
		const val LOGIN_URL = "https://api.bilimclass.kz/api/v2/os/login"
		const val USER_AGENT = "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
		const val DIARY_WEEKS_PATH = "/api/v4/os/clientoffice/diary/weeks"
		const val DIARY_PATH = "/api/v4/os/clientoffice/diary"
		const val DIARY_PERIODS_PATH = "/api/v4/os/clientoffice/diary/periods"
		const val SUBJECTS_PATH = "/api/v4/os/clientoffice/diary/subjects"
		const val YEAR_GRADES_PATH = "/api/v4/os/clientoffice/diary/year"
		const val SCHEDULE_PATH = "/api/v4/os/clientoffice/schedule"

		fun buildUrl(path: String, parameters: Map<String, String>): String {
			if (parameters.isEmpty()) return "https://api.bilimclass.kz$path"
			val query = parameters.entries.joinToString("&") { (key, value) ->
				"${encode(key)}=${encode(value)}"
			}
			return "https://api.bilimclass.kz$path?$query"
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
			val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			return HttpResponseData(connection.responseCode, connection.contentType.orEmpty(), body)
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
