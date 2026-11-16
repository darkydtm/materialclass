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
	fun replaceTokensIfCurrent(session: ActiveSession, expectedRefreshToken: String, accessToken: String, refreshToken: String, expiresAt: Instant): Boolean
	fun invalidateIfCurrent(session: ActiveSession, expectedRefreshToken: String): Boolean
	fun clearIfCurrent(session: ActiveSession?): Boolean
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
	private val refreshLock = Any()
	constructor(gateway: SchoolGateway, sessions: SessionController) : this(
		gateway,
		sessions,
		ContractBlockedIdentityVerifier,
	)
	fun login(credentials: LoginCredentials): LoginResult {
		val requestSession = sessions.currentSession()
		return when (val result = gateway.login(credentials)) {
			is GatewayLoginResult.Failure -> {
				sessions.clearIfCurrent(requestSession)
				signedInUser = null
				lastLoginResponse = null
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
		val user = sessions.userInfoFor(session)
		if (sessions.refreshTokenFor(session).isNullOrBlank() || user == null ||
			user.userId != session.accountId.value || identityVerifier.verify(user) != IdentityDecision.ConfirmedStudent) {
			sessions.clearIfCurrent(session)
			return false
		}
		if (sessions.currentSession() != session) return false
		signedInUser = user
		lastLoginResponse = null
		return true
	}

	fun refreshSession(session: ActiveSession): Boolean = when (refresh(session)) {
		is RefreshResult.Refreshed -> true
		else -> false
	}

	fun refresh(): RefreshResult {
		val session = sessions.currentSession() ?: return RefreshResult.LoginRequired
		return refresh(session)
	}

	fun refresh(session: ActiveSession): RefreshResult {
		val currentSession = sessions.currentSession() ?: return RefreshResult.LoginRequired
		if (currentSession != session) return RefreshResult.LoginRequired
		val requestedToken = sessions.refreshTokenFor(session) ?: return RefreshResult.LoginRequired
		return synchronized(refreshLock) {
			if (sessions.currentSession() != session) return@synchronized RefreshResult.LoginRequired
			val currentToken = sessions.refreshTokenFor(session) ?: return@synchronized RefreshResult.LoginRequired
			if (currentToken != requestedToken) return@synchronized RefreshResult.Refreshed(session)
			when (val result = gateway.refresh(requestedToken)) {
				is GatewayRefreshResult.Success -> {
					if (!sessions.replaceTokensIfCurrent(
						session,
						requestedToken,
						result.response.accessToken,
						result.response.refreshToken,
						result.response.expiresAt,
					)) {
						return@synchronized RefreshResult.LoginRequired
					}
					lastLoginResponse = null
					RefreshResult.Refreshed(session)
				}
				is GatewayRefreshResult.Failure -> if (result.error == AppError.LoginRequired) {
					sessions.invalidateIfCurrent(session, requestedToken)
					RefreshResult.LoginRequired
				} else RefreshResult.Failed(result.error)
			}
		}
	}

	fun refreshSession(): Boolean = when (refresh()) {
		is RefreshResult.Refreshed -> true
		else -> false
	}

	fun startTwoFactor(): TwoFactorResult = TwoFactorResult.Unsupported

	fun diaryWeeks(context: ReadContext): GatewayReadResult<DiaryWeeksModel> = gateway.diaryWeeks(context)

	fun diary(context: ReadContext): GatewayReadResult<DiaryModel> = gateway.diary(context)

	fun diaryPeriods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.diaryPeriods(context)

	fun periods(context: ReadContext): GatewayReadResult<PeriodsModel> = gateway.periods(context)

	fun subjects(context: ReadContext): GatewayReadResult<SubjectsModel> = gateway.subjects(context)

	fun grades(context: ReadContext): GatewayReadResult<GradesModel> {
		val subjectContext = context.copy(queryParameters = context.queryParameters.filterKeys { it in setOf("schoolId", "eduYear", "periodType", "period", "groupId") })
		val subjects = when (val result = gateway.subjects(subjectContext)) {
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
			queryParameters = context.queryParameters.filterKeys { it in setOf("userId", "studentGroupUuids", "studentGroupUuid", "eduYear") } + mapOf(
				"dateFrom" to journalDate(firstDate),
				"dateTo" to journalDate(lastDate),
			),
		)
		val journal = when (val result = gateway.journalGrades(journalContext)) {
			is GatewayReadResult.Success -> result.value
			GatewayReadResult.Empty -> null
			is GatewayReadResult.Failure -> return GatewayReadResult.Failure(result.error)
		}
		val bySchedule = journal?.grades.orEmpty()
			.filter { !it.scheduleUuid.isNullOrBlank() }
			.groupBy { it.scheduleUuid }
			.mapValues { (_, grades) ->
				val seenMarks = mutableSetOf<Pair<String, String?>>()
				val seenSors = mutableSetOf<Pair<String, String?>>()
				val seenSochs = mutableSetOf<Pair<String, String?>>()
				grades.map { grade ->
					grade.copy(
						mark = grade.mark?.takeIf { seenMarks.add(it to grade.markMax) },
						sor = grade.sor?.takeIf { seenSors.add(it to grade.sorMax) },
						soch = grade.soch?.takeIf { seenSochs.add(it to grade.sochMax) },
					)
				}.filter { it.mark != null || it.sor != null || it.soch != null || it.attendance != null }
			}
		return GatewayReadResult.Success(
			GradesModel(
				subjects.subjectCount,
				subjects.subjects.map { subject ->
					subject.copy(schedules = subject.schedules.flatMap { schedule ->
						val matchingGrades = schedule.uuid?.let { bySchedule[it] }.orEmpty().ifEmpty { listOf(null) }
						matchingGrades.map { grade ->
							schedule.copy(score = grade?.mark, markMax = grade?.markMax?.toIntOrNull() ?: schedule.markMax, attendance = grade?.attendance, sor = grade?.sor, sorMax = grade?.sorMax, soch = grade?.soch, sochMax = grade?.sochMax)
						}
					})
				},
			),
		)
	}

	fun schedule(context: ReadContext): GatewayReadResult<ScheduleModel> = gateway.schedule(context)

	fun homeworks(context: ReadContext): GatewayReadResult<HomeworksModel> = gateway.homeworks(context)

	fun homeworkDetail(context: ReadContext): GatewayReadResult<HomeworkDetailModel> = gateway.homeworkDetail(context)

	fun journalGrades(context: ReadContext): GatewayReadResult<JournalGradesModel> = gateway.journalGrades(context)

	fun gradeComparison(context: ReadContext, uuid: String, type: String) = gateway.gradeComparison(context, uuid, type)

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
				parameters["studentGroupUuids"] = user.studentGroupUuids.ifEmpty { listOfNotNull(user.studentGroupUuid) }.joinToString(",")
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
				parameters["studentGroupUuids"] = user.studentGroupUuids.ifEmpty { listOfNotNull(user.studentGroupUuid) }.joinToString(",")
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
