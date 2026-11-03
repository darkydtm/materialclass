package org.materialclass.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIdentityVerifierTest {
	@Test
	fun providesReadOnlyIdentityVerifier() {
		val type = try { Class.forName("org.materialclass.data.NativeIdentityVerifier") } catch (_: ClassNotFoundException) { null }
		assertNotNull("Native token verification must be available", type)
	}
}
