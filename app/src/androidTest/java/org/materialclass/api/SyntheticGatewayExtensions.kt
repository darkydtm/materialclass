package org.materialclass.api

import org.materialclass.session.RequestLease

internal fun NativeGateway.login(login: String, password: CharArray): GatewayResult<UnverifiedSession> = login(login, password, RequestLease())
internal fun NativeGateway.diary(session: VerifiedDiarySession, context: DiaryContext): GatewayResult<Diary> = diary(session, context, RequestLease())
internal fun NativeGateway.schedule(session: VerifiedScheduleSession, date: String): GatewayResult<Schedule> = schedule(session, date, RequestLease())
