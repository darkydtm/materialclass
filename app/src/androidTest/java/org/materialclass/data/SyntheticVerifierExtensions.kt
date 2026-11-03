package org.materialclass.data

import org.materialclass.api.UnverifiedSession
import org.materialclass.session.RequestLease
import org.materialclass.session.SessionVerification

internal fun NativeIdentityVerifier.verify(session: UnverifiedSession): SessionVerification = verify(session, RequestLease())
