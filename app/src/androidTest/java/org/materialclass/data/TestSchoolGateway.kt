package org.materialclass.data

import org.materialclass.api.*
import org.materialclass.session.RequestLease

internal abstract class TestSchoolGateway : SchoolGateway {
	override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Failure(AppError.UNAVAILABLE)
}
