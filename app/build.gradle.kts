plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
}

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
	releaseStoreFile,
	releaseStorePassword,
	releaseKeyAlias,
	releaseKeyPassword,
).all { !it.isNullOrBlank() }
val debugStoreFile = providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_PATH").orNull
val debugStorePassword = providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_PASSWORD").orNull
val debugKeyAlias = providers.environmentVariable("ANDROID_DEBUG_KEY_ALIAS").orNull
val debugKeyPassword = providers.environmentVariable("ANDROID_DEBUG_KEY_PASSWORD").orNull
val debugSigningConfigured = listOf(
	debugStoreFile,
	debugStorePassword,
	debugKeyAlias,
	debugKeyPassword,
).all { !it.isNullOrBlank() }
val releaseTaskRequested = gradle.startParameter.taskNames.any {
	it.contains("Release", ignoreCase = true)
}
val debugBuildTaskRequested = gradle.startParameter.taskNames.any {
	it.contains("assembleDebug", ignoreCase = true) ||
		it.contains("bundleDebug", ignoreCase = true)
}

if (releaseTaskRequested && !releaseSigningConfigured) {
	throw GradleException(
		"Release signing is not configured. Set ANDROID_KEYSTORE_PATH, " +
			"ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD.",
	)
}
if (debugBuildTaskRequested && !debugSigningConfigured) {
	throw GradleException(
		"Debug signing is not configured. Set ANDROID_DEBUG_KEYSTORE_PATH, " +
			"ANDROID_DEBUG_KEYSTORE_PASSWORD, ANDROID_DEBUG_KEY_ALIAS, and " +
			"ANDROID_DEBUG_KEY_PASSWORD.",
	)
}

android {
	namespace = "org.materialclass"
	compileSdk = 37

	if (releaseSigningConfigured) {
		signingConfigs {
			create("release") {
				storeFile = file(releaseStoreFile!!)
				storePassword = releaseStorePassword
				keyAlias = releaseKeyAlias
				keyPassword = releaseKeyPassword
			}
		}
	}
	if (debugSigningConfigured) {
		signingConfigs {
			create("debugCustom") {
				storeFile = file(debugStoreFile!!)
				storePassword = debugStorePassword
				keyAlias = debugKeyAlias
				keyPassword = debugKeyPassword
			}
		}
	}

	defaultConfig {
		applicationId = "org.materialclass"
		minSdk = 24
		targetSdk = 36
		versionCode = 1
		versionName = "0.1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildFeatures {
		compose = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		isCoreLibraryDesugaringEnabled = true
	}

	buildTypes {
		getByName("debug") {
			if (debugSigningConfigured) {
				signingConfig = signingConfigs.getByName("debugCustom")
			}
		}
		getByName("release") {
			if (releaseSigningConfigured) {
				signingConfig = signingConfigs.getByName("release")
			}
		}
	}
}

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material.icons)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.work.runtime)
	coreLibraryDesugaring(libs.android.desugar.jdk.libs)

	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
