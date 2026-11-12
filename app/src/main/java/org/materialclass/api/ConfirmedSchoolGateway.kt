package org.materialclass.api

import java.time.Instant
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession

interface SessionController {
	fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession
	fun invalidateAndClear()
}

sealed interface IdentityDecision {
	data object ConfirmedStudent : IdentityDecision
	data object DisallowedRole : IdentityDecision
	data object NotConfirmed : IdentityDecision
}

fun interface IdentityVerifier {
	fun verify(userInfo: UserInfo): IdentityDecision
}

internal class SyntheticConfirmedStudentVerifier(private val expectedAccountId: AccountId) : IdentityVerifier {
	override fun verify(userInfo: UserInfo): IdentityDecision =
		if (userInfo.userId == expectedAccountId.value) IdentityDecision.ConfirmedStudent else IdentityDecision.NotConfirmed
}

object ContractBlockedIdentityVerifier : IdentityVerifier {
	override fun verify(userInfo: UserInfo): IdentityDecision = IdentityDecision.NotConfirmed
}

object DisallowedRoleVerifier : IdentityVerifier {
	override fun verify(userInfo: UserInfo): IdentityDecision = IdentityDecision.DisallowedRole
}

sealed interface LoginResult {
	data class Confirmed(val session: ActiveSession) : LoginResult
	data class Failure(val error: AppError) : LoginResult
}

sealed interface RefreshResult {
	data object LoginRequired : RefreshResult
}

sealed interface TwoFactorResult {
	data object Unsupported : TwoFactorResult
}

class ConfirmedSchoolGateway(
	private val gateway: SchoolGateway,
	private val sessions: SessionController,
	private val identityVerifier: IdentityVerifier,
) {
	constructor(gateway: SchoolGateway, sessions: SessionController) : this(
		gateway,
		sessions,
		ContractBlockedIdentityVerifier,
	)
	fun login(credentials: LoginCredentials): LoginResult {
		return when (val result = gateway.login(credentials)) {
			is GatewayLoginResult.Failure -> {
				sessions.invalidateAndClear()
				LoginResult.Failure(result.error)
			}
			is GatewayLoginResult.Success -> when (identityVerifier.verify(result.response.userInfo)) {
				IdentityDecision.ConfirmedStudent -> try {
					LoginResult.Confirmed(
						sessions.activate(
							AccountId(result.response.userInfo.userId),
							result.response.accessToken,
							result.response.expiresAt,
						),
					)
				} catch (_: Exception) {
					sessions.invalidateAndClear()
					LoginResult.Failure(AppError.SessionActivationFailed)
				}
				IdentityDecision.DisallowedRole -> {
					sessions.invalidateAndClear()
					LoginResult.Failure(AppError.DisallowedRole)
				}
				IdentityDecision.NotConfirmed -> {
					sessions.invalidateAndClear()
					LoginResult.Failure(AppError.IdentityNotConfirmed)
				}
			}
		}
	}

	fun refresh(): RefreshResult {
		// Keep refresh disabled until its route, body, rotation and expiry contract are observed.
		sessions.invalidateAndClear()
		return RefreshResult.LoginRequired
	}

	fun startTwoFactor(): TwoFactorResult = TwoFactorResult.Unsupported
}
