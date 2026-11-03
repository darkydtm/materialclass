package org.materialclass.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.materialclass.api.NativeGateway
import org.materialclass.session.SessionVerifier
import org.junit.Test
import org.materialclass.api.SchoolGateway
import org.materialclass.session.RequestLease

class LeaseContractTest {
	@Test
	fun networkEntryPointsRequireLeases() {
		listOf(SchoolGateway::class.java, IdentityVerifier::class.java, SessionVerifier::class.java, NativeGateway::class.java, NativeIdentityVerifier::class.java).forEach { type ->
			type.declaredMethods.filter { it.name.substringBefore('$') in setOf("login", "diary", "schedule", "verify") }.forEach { method ->
				assertTrue("${type.name}.${method.name} must require a lease", method.parameterTypes.contains(RequestLease::class.java))
			}
		}
	}

	@Test
	fun leaseMethodsHaveNoFallbackImplementation() {
		listOf(SchoolGateway::class.java, IdentityVerifier::class.java).forEach { contract ->
			val defaults = try { Class.forName(contract.name + "$" + "DefaultImpls") } catch (_: ClassNotFoundException) { null }
			assertFalse(defaults?.declaredMethods?.any { method -> method.parameterTypes.contains(RequestLease::class.java) } == true)
			assertFalse(contract.declaredMethods.any { method -> method.parameterTypes.contains(RequestLease::class.java) && method.isDefault })
		}
	}
}
