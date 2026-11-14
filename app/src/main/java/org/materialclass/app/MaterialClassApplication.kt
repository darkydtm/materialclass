package org.materialclass.app

import android.app.Application
import java.time.Instant
import java.time.LocalDate
import org.materialclass.data.DateRange
import org.materialclass.notifications.NotificationScheduler
import org.materialclass.ui.AppPreferences
import org.materialclass.api.ConfirmedSchoolGateway
import org.materialclass.api.ReadContext
import org.materialclass.api.SchoolGateway
import org.materialclass.api.StudentRoleVerifier
import org.materialclass.api.UrlConnectionTransport
import org.materialclass.cache.CacheFiles
import org.materialclass.data.EncryptedSnapshotStore
import org.materialclass.data.RepositoryGateway
import org.materialclass.data.SchoolRepositoryImpl
import org.materialclass.security.AndroidKeystoreKeys
import org.materialclass.security.KeystoreCipher
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionTaskRegistry

class MaterialClassApplication : Application() {
	val runtime: ApplicationRuntime by lazy { ApplicationRuntime(this) }
}

class ApplicationRuntime(application: Application) {
	val gateway: ConfirmedSchoolGateway
	val repository: SchoolRepositoryImpl
	private val sessionCoordinator: SessionCoordinator
	private val preferences = AppPreferences(application)
	private val applicationContext = application
	val startAuthenticated: Boolean get() = sessionCoordinator.currentSession() != null

	init {
		val files = CacheFiles(application.filesDir)
		val keys = AndroidKeystoreKeys()
		val cipher = KeystoreCipher(keys)
		val sessionStore = SessionStore(files, keys, cipher)
		sessionCoordinator = SessionCoordinator(sessionStore, files, keys, cipher, object : SessionTaskRegistry {
			override fun cancelAll() = NotificationScheduler(application).cancel()
		})
		sessionCoordinator.restore(Instant.now())
		val confirmedGateway = ConfirmedSchoolGateway(
			SchoolGateway(UrlConnectionTransport()),
			sessionCoordinator,
			StudentRoleVerifier,
		)
		confirmedGateway.restoreSession()
		repository = SchoolRepositoryImpl(
			gateway = object : RepositoryGateway {
				override fun home(context: ReadContext) = confirmedGateway.home()
				override fun diary(context: ReadContext) = confirmedGateway.diary(context)
				override fun schedule(context: ReadContext) = confirmedGateway.schedule(context)
				override fun grades(context: ReadContext) = confirmedGateway.grades(context)
				override fun homeworks(context: ReadContext) = confirmedGateway.homeworks(context)
				override fun journalGrades(context: ReadContext) = confirmedGateway.journalGrades(context)
				override fun profile() = confirmedGateway.profile()
				override fun logout() = confirmedGateway.logout()
			},
			sessions = sessionCoordinator,
			store = EncryptedSnapshotStore(sessionCoordinator.cache(), sessionCoordinator),
			context = confirmedGateway::readContext,
		)
		gateway = confirmedGateway
	}

	fun updateBackgroundSchedule() {
		sessionCoordinator.withSessionLock {
			val scheduler = NotificationScheduler(applicationContext, preferences.backgroundInterval().toLong())
			if (preferences.backgroundWork() && sessionCoordinator.currentSession() != null) scheduler.schedule() else scheduler.cancel()
		}
	}

	fun refreshBackgroundCache() {
		if (!preferences.backgroundWork()) return
		val session = sessionCoordinator.currentSession() ?: return
		val today = LocalDate.now()
		val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
		repository.refreshScheduleFor(session, DateRange(monday, monday.plusDays(6)))
	}
}
