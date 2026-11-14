package org.materialclass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.materialclass.ui.MaterialClassApp
import org.materialclass.api.GatewayLoginResult

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val runtime = (application as MaterialClassApplication).runtime
		setContent {
			MaterialClassApp(
				repository = runtime.repository,
				loginHandler = { credentials ->
					runtime.gateway.loginForUi(credentials).also { if (it is GatewayLoginResult.Success) runtime.updateBackgroundSchedule() }
				},
				onBackgroundSettingsChanged = runtime::updateBackgroundSchedule,
				onNotificationSettingsChange = runtime::changeNotificationSettings,
				startAuthenticated = runtime.startAuthenticated,
			)
		}
	}
}
