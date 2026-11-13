package org.materialclass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.materialclass.api.ConfirmedSchoolGateway
import org.materialclass.api.SchoolGateway
import org.materialclass.api.StudentRoleVerifier
import org.materialclass.api.UrlConnectionTransport
import org.materialclass.cache.CacheFiles
import org.materialclass.data.EncryptedSnapshotStore
import org.materialclass.data.SchoolRepositoryImpl
import org.materialclass.security.AndroidKeystoreKeys
import org.materialclass.security.KeystoreCipher
import org.materialclass.session.SessionCoordinator
import org.materialclass.session.SessionStore
import org.materialclass.session.SessionTaskRegistry
import org.materialclass.ui.MaterialClassApp

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val files = CacheFiles(filesDir)
		val keys = AndroidKeystoreKeys()
		val cipher = KeystoreCipher(keys)
		val sessionStore = SessionStore(files, keys, cipher)
		val sessionCoordinator = SessionCoordinator(sessionStore, files, keys, cipher, object : SessionTaskRegistry {
			override fun cancelAll() = Unit
		})
		sessionCoordinator.restore(java.time.Instant.now())
		val confirmedGateway = ConfirmedSchoolGateway(
			SchoolGateway(UrlConnectionTransport()),
			sessionCoordinator,
			StudentRoleVerifier,
		)
		val startAuthenticated = confirmedGateway.restoreSession()
		val repository = SchoolRepositoryImpl(
			gateway = object : org.materialclass.data.RepositoryGateway {
				override fun home(context: org.materialclass.api.ReadContext) = confirmedGateway.home()
				override fun diary(context: org.materialclass.api.ReadContext) = confirmedGateway.diary(context)
				override fun schedule(context: org.materialclass.api.ReadContext) = confirmedGateway.schedule(context)
				override fun grades(context: org.materialclass.api.ReadContext) = confirmedGateway.grades(context)
				override fun homeworks(context: org.materialclass.api.ReadContext) = confirmedGateway.homeworks(context)
				override fun journalGrades(context: org.materialclass.api.ReadContext) = confirmedGateway.journalGrades(context)
				override fun profile() = confirmedGateway.profile()
				override fun logout() = confirmedGateway.logout()
			},
			sessions = sessionCoordinator,
			store = EncryptedSnapshotStore(sessionCoordinator.cache(), sessionCoordinator),
			context = confirmedGateway::readContext,
		)
		setContent {
			MaterialClassApp(
				repository = repository,
				loginHandler = confirmedGateway::loginForUi,
				startAuthenticated = startAuthenticated,
			)
		}
	}
}
