package org.materialclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
	@Test
	fun routeStackStartsAtLoginAndBackDoesNotLeaveRoot() {
		val navigator = AppNavigator(AppRoute.Login)
		navigator.go(AppRoute.Schedule)
		navigator.go(AppRoute.LessonDetail)

		assertEquals(AppRoute.LessonDetail, navigator.current)
		assertTrue(navigator.back())
		assertEquals(AppRoute.Schedule, navigator.current)
		assertTrue(navigator.back())
		assertEquals(AppRoute.Login, navigator.current)
		assertEquals(false, navigator.back())
	}

	@Test
	fun unsupportedContractIsRenderedAsUnavailableState() {
		assertEquals(UiContent.Unsupported, UiContent.from(DataStateKind.Unsupported))
		assertEquals(UiContent.Empty, UiContent.from(DataStateKind.Empty))
		assertEquals(UiContent.Error, UiContent.from(DataStateKind.Error))
	}
}
