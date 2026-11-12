plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
}

android {
	namespace = "org.materialclass"
	compileSdk = 37

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
	}
}

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.work.runtime)

	testImplementation(libs.junit)
}
