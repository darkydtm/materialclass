package org.materialclass.notifications

import org.materialclass.api.AppError
import org.materialclass.api.ConfirmedSchoolGateway
import org.materialclass.api.GatewayReadResult
import org.materialclass.api.ReadContext
import org.materialclass.data.ActiveSession
import org.materialclass.data.DateRange
import org.materialclass.data.HomeworksModel
import org.materialclass.data.JournalGradesModel
import org.materialclass.data.SessionAccess

class ProductionNotificationDataSource(
	private val sessions: SessionAccess,
	private val context: (String, String) -> ReadContext,
	private val journalRange: () -> DateRange?,
	private val journal: (ReadContext) -> GatewayReadResult<JournalGradesModel>,
	private val homeworks: (ReadContext) -> GatewayReadResult<HomeworksModel>,
) : NotificationDataSource {
	constructor(
		sessions: SessionAccess,
		gateway: ConfirmedSchoolGateway,
		journalRange: () -> DateRange?,
	) : this(sessions, gateway::readContext, journalRange, gateway::journalGrades, gateway::homeworks)

	override fun readNewGrades(session: NotificationSession): CategoryReadResult = read(session.activeSession) { token ->
		val range = journalRange() ?: return@read CategoryReadResult.Failed
		when (val result = journal(context(token, "journal:${range.start}:${range.endInclusive}"))) {
			is GatewayReadResult.Success -> {
				if (result.value.groupsFailed != 0 || result.value.groupsSucceeded == 0) CategoryReadResult.Failed
				else CategoryReadResult.Available(result.value.grades.mapNotNull { grade ->
					val id = grade.scheduleUuid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
					val mark = grade.mark?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
					NotificationItem(NotificationCategory.NEW_GRADE, "${id.length}:$id$mark", "Grade", mark)
				}.distinctBy { it.stableId })
			}
			GatewayReadResult.Empty -> CategoryReadResult.Available(emptyList())
			is GatewayReadResult.Failure -> failure(result.error)
		}
	}

	override fun readHomework(session: NotificationSession): CategoryReadResult = read(session.activeSession) { token ->
		when (val result = homeworks(context(token, "homeworks"))) {
			is GatewayReadResult.Success -> CategoryReadResult.Available(result.value.items.mapNotNull { item ->
				val id = item.uuid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				NotificationItem(NotificationCategory.HOMEWORK, id, item.subjectName?.takeIf { it.isNotBlank() } ?: "Homework", item.body ?: item.theme.orEmpty())
			}.distinctBy { it.stableId })
			GatewayReadResult.Empty -> CategoryReadResult.Available(emptyList())
			is GatewayReadResult.Failure -> failure(result.error)
		}
	}

	private fun read(session: ActiveSession, block: (String) -> CategoryReadResult): CategoryReadResult {
		if (sessions.currentSession() != session) return CategoryReadResult.Failed
		val token = sessions.tokenFor(session)?.takeIf { it.isNotBlank() } ?: return CategoryReadResult.Failed
		val result = try {
			block(token)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw exception
		} catch (exception: java.util.concurrent.CancellationException) {
			throw exception
		} catch (_: Exception) {
			CategoryReadResult.Failed
		}
		return if (sessions.currentSession() == session) result else CategoryReadResult.Failed
	}

	private fun failure(error: AppError): CategoryReadResult = when (error) {
		AppError.Offline -> CategoryReadResult.Offline
		AppError.UnsupportedContract -> CategoryReadResult.Unsupported
		else -> CategoryReadResult.Failed
	}
}
