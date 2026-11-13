package org.materialclass.api

import java.time.Instant
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.GradesModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.ProfileModel
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SubjectsModel

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

object StudentRoleVerifier : IdentityVerifier {
	override fun verify(userInfo: UserInfo): IdentityDecision =
		if (userInfo.hasStudentContext) IdentityDecision.ConfirmedStudent else IdentityDecision.NotConfirmed
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
	private var signedInUser: UserInfo? = null
	private var lastLoginResponse: LoginResponse? = null
	constructor(gateway: SchoolGateway, sessions: SessionController) : this(
		gateway,
		sessions,
		ContractBlockedIdentityVerifier,
	)
	fun login(credentials: LoginCredentials): LoginResult {
		return when (val result = gateway.login(credentials)) {
			is GatewayLoginResult.Failure -> {
				signedInUser = null
				lastLoginResponse = null
				sessions.invalidateAndClear()
				LoginResult.Failure(result.error)
			}
			is GatewayLoginResult.Success -> when (identityVerifier.verify(result.response.userInfo)) {
				IdentityDecision.ConfirmedStudent -> try {
					signedInUser = result.response.userInfo
					lastLoginResponse = result.response
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
					signedInUser = null
					lastLoginResponse = null
					sessions.invalidateAndClear()
					LoginResult.Failure(AppError.DisallowedRole)
				}
				IdentityDecision.NotConfirmed -> {
					signedInUser = null
					lastLoginResponse = null
					sessions.invalidateAndClear()
					LoginResult.Failure(AppError.IdentityNotConfirmed)
				}
			}
		}
	}

	fun loginForUi(credentials: LoginCredentials): GatewayLoginResult = when (val result = login(credentials)) {
		is LoginResult.Confirmed -> lastLoginResponse?.let { GatewayLoginResult.Success(it) }
			?: GatewayLoginResult.Failure(AppError.InvalidResponse)
		is LoginResult.Failure -> GatewayLoginResult.Failure(result.error)
	}

	fun refresh(): RefreshResult {
		// Keep refresh disabled until its route, body, rotation and expiry contract are observed.
		sessions.invalidateAndClear()
		return RefreshResult.LoginRequired
	}

	fun startTwoFactor(): TwoFactorResult = TwoFactorResult.Unsupported

	fun diaryWeeks(context: ReadContext): GatewayReadResult<DiaryWeeksModel> = gateway.diaryWeeks(context)

	fun diary(context: ReadContext): GatewayReadResult<DiaryModel> = gateway.diary(context)

	fun diaryPeriods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.diaryPeriods(context)

	fun periods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.periods(context)

	fun subjects(context: ReadContext): GatewayReadResult<SubjectsModel> = gateway.subjects(context)

	fun grades(context: ReadContext): GatewayReadResult<GradesModel> = gateway.grades(context)

	fun schedule(context: ReadContext): GatewayReadResult<ScheduleModel> = gateway.schedule(context)

	fun home(): GatewayReadResult<org.materialclass.data.HomeModel> = unsupported()

	fun scheduleWeeks(context: ReadContext): GatewayReadResult<ScheduleModel> = gateway.schedule(context)

	fun lessonDetail(lessonId: String): GatewayReadResult<org.materialclass.data.LessonDetailModel> = unsupported()

	fun attendance(context: ReadContext): GatewayReadResult<org.materialclass.data.AttendanceModel> = unsupported()

	fun subjectDetails(subjectId: String): GatewayReadResult<org.materialclass.data.SubjectDetailsModel> = unsupported()

	fun profile(): GatewayReadResult<ProfileModel> = signedInUser?.let { user ->
		GatewayReadResult.Success(
			ProfileModel(
				accountId = AccountId(user.userId),
				schoolLabel = user.schoolName,
				groupLabel = user.groupName,
				fullName = user.fullName,
				region = user.region,
				schoolYear = user.eduYear?.toIntOrNull(),
			),
		)
	} ?: unsupported()

	fun studentContext(): GatewayReadResult<org.materialclass.data.ProfileModel> = profile()

	fun readContext(token: String, key: String): ReadContext {
		val user = signedInUser ?: return ReadContext(token)
		val parameters = linkedMapOf(
			"schoolId" to user.schoolId.orEmpty(),
			"eduYear" to user.eduYear.orEmpty(),
		)
		when {
			key.startsWith("diary:") -> {
				val date = key.substringAfter(':').substringBefore(':')
				parameters["date"] = runCatching {
					java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
				}.getOrDefault(date)
				user.groupId?.let { parameters["groupId"] = it }
			}
			key.startsWith("schedule:") -> {
				val date = key.substringAfter(':').substringBefore(':')
				parameters["date"] = runCatching {
					java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
				}.getOrDefault(date)
				user.studentSchoolUuid?.let { parameters["studentSchoolUuid"] = it }
			}
			key.startsWith("subjects:") -> {
				parameters["periodType"] = "quarter"
				parameters["period"] = key.substringAfter(':')
				user.groupId?.let { parameters["groupId"] = it }
			}
		}
		return ReadContext(token, parameters.filterValues { it.isNotBlank() })
	}

	private fun <T> unsupported(): GatewayReadResult<T> = GatewayReadResult.Failure(AppError.UnsupportedContract)
}
