package org.materialclass.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherMessageTest {
	@Test
	fun launcherMessageUsesTheProductName() {
		assertEquals("MaterialClass", launcherMessage)
	}
}
