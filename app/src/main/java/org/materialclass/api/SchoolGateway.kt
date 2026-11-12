package org.materialclass.api

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
) {
	fun login(credentials: LoginCredentials): GatewayLoginResult {
		if (credentials.login.isBlank() || credentials.password.isBlank()) {
			return GatewayLoginResult.Failure(AppError.InvalidCredentials)
		}
		val request = HttpRequestData(
			method = "POST",
			url = LOGIN_URL,
			headers = mapOf("Content-Type" to "application/json"),
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

	private companion object {
		const val LOGIN_URL = "https://api.bilimclass.kz/api/v2/os/login"

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

data class LoginCredentials(
	val login: String,
	val password: String,
)

sealed interface GatewayLoginResult {
	data class Success(val response: LoginResponse) : GatewayLoginResult
	data class Failure(val error: AppError) : GatewayLoginResult
}
