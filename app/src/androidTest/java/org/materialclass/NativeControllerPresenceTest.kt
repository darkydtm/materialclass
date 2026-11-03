package org.materialclass

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeControllerPresenceTest {
	@Test
	fun nativeControllerIsAvailable() {
		val present = try { Class.forName("org.materialclass.NativeController"); true } catch (_: ClassNotFoundException) { false }
		assertTrue("Native controller must connect the UI to the repository", present)
	}
}
