package org.materialclass.api

sealed interface AppError {
	data object Offline : AppError
	data object Timeout : AppError
	data object Network : AppError
	data object TlsFailure : AppError
	data object Unauthorized : AppError
	data object InvalidCredentials : AppError
	data object Forbidden : AppError
	data object DisallowedRole : AppError
	data object IdentityNotConfirmed : AppError
	data object NotFound : AppError
	data object Server : AppError
	data object InvalidResponse : AppError
	data object SessionActivationFailed : AppError
	data object LoginRequired : AppError
	data object UnsupportedContract : AppError
	data class HttpStatus(val code: Int) : AppError {
		init {
			require(code in 100..599) { "HTTP status must be between 100 and 599" }
		}
	}
	data object Unknown : AppError
}
