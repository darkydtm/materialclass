package org.materialclass.data

import org.junit.Assert.assertFalse
import org.junit.Test
import org.materialclass.api.SchoolGateway
import org.materialclass.session.RequestLease

class LeaseContractTest {
	@Test
	fun leaseMethodsHaveNoFallbackImplementation() {
		listOf(SchoolGateway::class.java, IdentityVerifier::class.java).forEach { contract ->
			val defaults = try { Class.forName(contract.name + "$" + "DefaultImpls") } catch (_: ClassNotFoundException) { null }
			assertFalse(defaults?.declaredMethods?.any { method -> method.parameterTypes.contains(RequestLease::class.java) } == true)
			assertFalse(contract.declaredMethods.any { method -> method.parameterTypes.contains(RequestLease::class.java) && method.isDefault })
		}
	}
}
