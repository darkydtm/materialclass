package org.materialclass.session

import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureStorageAvailabilityTest {
	@Test
	fun exposesEncryptedSessionAndAccountCacheStores() {
		for (name in listOf(
			"org.materialclass.session.SessionStore",
			"org.materialclass.cache.EncryptedCache",
			"org.materialclass.security.KeystoreKeys"
		)) {
			val type = try {
				Class.forName(name)
			} catch (_: ClassNotFoundException) {
				null
			}
			assertNotNull("Secure storage requires $name", type)
		}
		}
}
