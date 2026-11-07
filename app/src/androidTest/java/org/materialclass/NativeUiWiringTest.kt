package org.materialclass

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeUiWiringTest {
	@Test
	fun nativeUiIsEnabledByDefault() {
		assertTrue("The default APK must wire the native UI", BuildConfig.NATIVE_UI_ENABLED)
	}
}
