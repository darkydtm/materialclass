plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "org.materialclass"
	compileSdk = 37

	defaultConfig {
		applicationId = "org.materialclass"
		minSdk = 24
		targetSdk = 37
		versionCode = 1
		versionName = "0.1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		buildConfigField("boolean", "NATIVE_UI_ENABLED", "false")
	}

	buildTypes {
		getByName("debug") {
			if (providers.gradleProperty("backupRulesProbe").orNull == "true") {
				applicationIdSuffix = ".backupprobe"
			}
			val integrationUi = providers.gradleProperty("integrationTestNativeUi").map { it == "true" }.getOrElse(false)
			buildConfigField("boolean", "NATIVE_UI_ENABLED", integrationUi.toString())
			buildConfigField("boolean", "INTEGRATION_TEST_NATIVE_UI", integrationUi.toString())
		}
	}

	if (providers.gradleProperty("backupRulesProbe").orNull == "true") {
		sourceSets.getByName("debug").manifest.srcFile("src/backupProbe/AndroidManifest.xml")
		sourceSets.getByName("debug").res.srcDir("src/backupProbe/res")
	}

	buildFeatures {
		buildConfig = true
		compose = true
	}
}

dependencies {
	implementation(platform(libs.compose.bom))
	implementation(libs.material3)
	implementation(libs.activity.compose)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.runner)
}
