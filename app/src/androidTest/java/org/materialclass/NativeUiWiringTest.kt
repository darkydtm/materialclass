package org.materialclass

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeUiWiringTest {
	@Test
	fun nativeUiIsDisabledByDefault() {
		val field = try { Class.forName("org.materialclass.BuildConfig").getField("NATIVE_UI_ENABLED") } catch (_: ReflectiveOperationException) { null }
		assertNotNull("The native UI must have a compile-time activation flag", field)
		if (BuildConfig.INTEGRATION_TEST_NATIVE_UI) assertTrue(field!!.getBoolean(null))
		else assertFalse(field!!.getBoolean(null))
	}
}
