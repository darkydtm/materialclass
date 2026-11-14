package org.materialclass.api

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.materialclass.data.AccountId
import org.materialclass.data.ActiveSession
import org.materialclass.data.AttendanceModel
import org.materialclass.data.DiaryModel
import org.materialclass.data.DiaryWeeksModel
import org.materialclass.data.GradesModel
import org.materialclass.data.PeriodsModel
import org.materialclass.data.ProfileModel
import org.materialclass.data.ScheduleModel
import org.materialclass.data.SubjectsModel
import org.materialclass.data.HomeModel
import org.materialclass.data.HomeworkDetailModel
import org.materialclass.data.HomeworksModel
import org.materialclass.data.JournalGradesModel
import org.materialclass.data.LessonDetailModel
import org.materialclass.data.SubjectDetailsModel
import org.materialclass.data.SystemDataModel

interface SessionController {
	fun activate(accountId: AccountId, token: String, expiresAt: Instant): ActiveSession
	fun setRefreshToken(session: ActiveSession, refreshToken: String): Boolean = false
	fun setUserInfo(session: ActiveSession, userInfo: UserInfo): Boolean = true
	fun currentSession(): ActiveSession? = null
	fun accessTokenFor(session: ActiveSession): String? = null
	fun refreshTokenFor(session: ActiveSession): String? = null
	fun userInfoFor(session: ActiveSession): UserInfo? = null
	fun replaceTokens(session: ActiveSession, accessToken: String, refreshToken: String, expiresAt: Instant): Boolean = false
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
	data class Refreshed(val session: ActiveSession) : RefreshResult
	data object LoginRequired : RefreshResult
	data class Failed(val error: AppError) : RefreshResult
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
					val session = sessions.activate(
						AccountId(result.response.userInfo.userId),
						result.response.accessToken,
						result.response.expiresAt,
					)
					if (!sessions.setRefreshToken(session, result.response.refreshToken)) {
						throw IllegalStateException("refresh token was not stored")
					}
					if (!sessions.setUserInfo(session, result.response.userInfo)) {
						throw IllegalStateException("user context was not stored")
					}
					LoginResult.Confirmed(session)
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

	fun restoreSession(): Boolean {
		val session = sessions.currentSession() ?: return false
		if (sessions.refreshTokenFor(session).isNullOrBlank()) {
			sessions.invalidateAndClear()
			return false
		}
		val user = sessions.userInfoFor(session) ?: run {
			sessions.invalidateAndClear()
			return false
		}
		signedInUser = user
		lastLoginResponse = null
		return true
	}

	fun refresh(): RefreshResult {
		val session = sessions.currentSession() ?: return RefreshResult.LoginRequired
		val refreshToken = sessions.refreshTokenFor(session) ?: return RefreshResult.LoginRequired
		return when (val result = gateway.refresh(refreshToken)) {
			is GatewayRefreshResult.Success -> {
				if (!sessions.replaceTokens(
						session,
						result.response.accessToken,
						result.response.refreshToken,
						result.response.expiresAt,
					)) {
					sessions.invalidateAndClear()
					RefreshResult.Failed(AppError.IdentityNotConfirmed)
				} else {
					lastLoginResponse = null
					RefreshResult.Refreshed(session)
				}
			}
			is GatewayRefreshResult.Failure -> if (result.error == AppError.LoginRequired) {
				sessions.invalidateAndClear()
				RefreshResult.LoginRequired
			} else RefreshResult.Failed(result.error)
		}
	}

	fun startTwoFactor(): TwoFactorResult = TwoFactorResult.Unsupported

	fun diaryWeeks(context: ReadContext): GatewayReadResult<DiaryWeeksModel> = gateway.diaryWeeks(context)

	fun diary(context: ReadContext): GatewayReadResult<DiaryModel> = gateway.diary(context)

	fun diaryPeriods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.diaryPeriods(context)

	fun periods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.periods(context)

	fun subjects(context: ReadContext): GatewayReadResult<SubjectsModel> = gateway.subjects(context)

	fun grades(context: ReadContext): GatewayReadResult<GradesModel> {
		val subjects = when (val result = gateway.subjects(context)) {
			is GatewayReadResult.Success -> result.value
			GatewayReadResult.Empty -> return GatewayReadResult.Empty
			is GatewayReadResult.Failure -> return GatewayReadResult.Failure(result.error)
		}
		val schedules = subjects.subjects.flatMap { it.schedules }
		if (schedules.isEmpty()) return GatewayReadResult.Success(GradesModel(subjects.subjectCount, subjects.subjects))
		val dates = schedules.mapNotNull { it.date }.sortedWith(compareBy { parseDate(it) })
		if (dates.isEmpty()) return GatewayReadResult.Success(GradesModel(subjects.subjectCount, subjects.subjects))
		val firstDate = dates.first()
		val lastDate = dates.last()
		val journalContext = context.copy(
			queryParameters = context.queryParameters + mapOf(
				"dateFrom" to journalDate(firstDate),
				"dateTo" to journalDate(lastDate),
			),
		)
		val journal = when (val result = gateway.journalGrades(journalContext)) {
			is GatewayReadResult.Success -> result.value
			GatewayReadResult.Empty -> null
			is GatewayReadResult.Failure -> null
		}
		val bySchedule = journal?.grades.orEmpty().associateBy { it.scheduleUuid }
		return GatewayReadResult.Success(
			GradesModel(
				subjects.subjectCount,
				subjects.subjects.map { subject ->
					subject.copy(schedules = subject.schedules.map { schedule ->
						val grade = bySchedule[schedule.uuid]
						schedule.copy(score = grade?.mark, attendance = grade?.attendance)
					})
				},
			),
		)
	}

	fun schedule(context: ReadContext): GatewayReadResult<ScheduleModel> = gateway.schedule(context)

	fun homeworks(context: ReadContext): GatewayReadResult<HomeworksModel> = gateway.homeworks(context)

	fun homeworkDetail(context: ReadContext): GatewayReadResult<HomeworkDetailModel> = gateway.homeworkDetail(context)

	fun journalGrades(context: ReadContext): GatewayReadResult<JournalGradesModel> = gateway.journalGrades(context)

	fun downloadSubjectsExcel(context: ReadContext): HttpResult = gateway.downloadSubjectsExcel(context)

	fun educationYears(context: ReadContext): GatewayReadResult<SystemDataModel> = gateway.educationYears(context)

	fun systemSettings(context: ReadContext): GatewayReadResult<SystemDataModel> = gateway.systemSettings(context)

	fun systemRelatedChildren(context: ReadContext): GatewayReadResult<SystemDataModel> = gateway.relatedChildren(context)

	fun logout(): GatewayReadResult<Unit> {
		val session = sessions.currentSession() ?: return GatewayReadResult.Failure(AppError.LoginRequired)
		val user = signedInUser ?: return GatewayReadResult.Failure(AppError.LoginRequired)
		val accessToken = sessions.accessTokenFor(session) ?: return GatewayReadResult.Failure(AppError.LoginRequired)
		val context = ReadContext(
			accessToken = accessToken,
			queryParameters = mapOf("schoolId" to user.schoolId.orEmpty(), "eduYear" to user.eduYear.orEmpty())
				.filterValues { it.isNotBlank() },
		)
		val result = gateway.logout(context)
		if (result is GatewayReadResult.Success) {
			signedInUser = null
			lastLoginResponse = null
			sessions.invalidateAndClear()
		}
		return result
	}

	fun home(): GatewayReadResult<HomeModel> = unsupported()

	fun scheduleWeeks(context: ReadContext): GatewayReadResult<ScheduleModel> = gateway.schedule(context)

	fun lessonDetail(lessonId: String): GatewayReadResult<LessonDetailModel> = unsupported()

	fun attendance(context: ReadContext): GatewayReadResult<AttendanceModel> = unsupported()

	fun subjectDetails(subjectId: String): GatewayReadResult<SubjectDetailsModel> = unsupported()

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

	fun studentContext(): GatewayReadResult<ProfileModel> = profile()

	private fun parseDate(value: String): java.time.LocalDate = runCatching {
		java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
	}.getOrDefault(java.time.LocalDate.MIN)

	private fun journalDate(value: String): String = runCatching {
		parseDate(value).toString() + "T00:00:00.000Z"
	}.getOrDefault(value + "T00:00:00.000Z")

	fun readContext(token: String, key: String): ReadContext {
		val user = signedInUser ?: return ReadContext(token)
		val parameters = linkedMapOf(
			"schoolId" to user.schoolId.orEmpty(),
			"eduYear" to user.eduYear.orEmpty(),
		)
		when {
			key.startsWith("diary:") -> {
				val date = key.substringAfter(':').substringBefore(':')
				parameters["date"] = formatRequestDate(date)
				user.groupId?.let { parameters["groupId"] = it }
			}
			key.startsWith("schedule:") -> {
				val date = key.substringAfter(':').substringBefore(':')
				parameters["date"] = formatRequestDate(date)
				user.studentSchoolUuid?.let { parameters["studentSchoolUuid"] = it }
			}
			key.startsWith("subjects:") -> {
				parameters["periodType"] = "quarter"
				parameters["period"] = key.substringAfter(':')
				user.groupId?.let { parameters["groupId"] = it }
			}
			key.startsWith("grades:") -> {
				parameters["periodType"] = "quarter"
				parameters["period"] = key.substringAfter(':')
				user.groupId?.let { parameters["groupId"] = it }
				parameters["userId"] = user.userId
				parameters["studentGroupUuids"] = user.studentGroupUuids.joinToString(",")
			}
			key == "homeworks" || key.startsWith("homeworks:") -> {
				user.studentGroupUuid?.let { parameters["studentGroupUuid"] = it }
			}
			key.startsWith("homework:") -> {
				parameters["homeworkUuid"] = key.substringAfter(':')
			}
			key.startsWith("subjectsExcel:") -> {
				parameters["periodType"] = "quarter"
				parameters["period"] = key.substringAfter(':')
				user.groupId?.let { parameters["groupId"] = it }
			}
			key.startsWith("journal:") -> {
				parameters["userId"] = user.userId
				parameters["studentGroupUuids"] = user.studentGroupUuids.joinToString(",")
				if (key.startsWith("journal:")) {
					parameters["dateFrom"] = key.substringAfter(':').substringBefore(':') + "T00:00:00.000Z"
					parameters["dateTo"] = key.substringAfterLast(':') + "T00:00:00.000Z"
				}
			}
		}
		return ReadContext(token, parameters.filterValues { it.isNotBlank() }, user.chatToken)
	}

	private fun formatRequestDate(value: String): String = runCatching {
		LocalDate.parse(value).format(requestDateFormatter)
	}.getOrDefault(value)

	private fun <T> unsupported(): GatewayReadResult<T> = GatewayReadResult.Failure(AppError.UnsupportedContract)
}

private val requestDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
