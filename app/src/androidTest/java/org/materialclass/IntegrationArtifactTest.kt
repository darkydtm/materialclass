package org.materialclass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationArtifactTest {
	@Test
	fun integrationArtifactEnablesNativeUi() {
		assumeTrue(InstrumentationRegistry.getArguments().getString("verifyIntegrationArtifact") == "true")
		val config = Class.forName("org.materialclass.BuildConfig")
		assertTrue(config.getField("INTEGRATION_TEST_NATIVE_UI").getBoolean(null))
		assertTrue(config.getField("NATIVE_UI_ENABLED").getBoolean(null))
	}
}
