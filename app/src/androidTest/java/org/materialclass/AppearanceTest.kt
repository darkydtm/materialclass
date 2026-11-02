package org.materialclass

import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
	@Test
	fun blackThemeUsesBlackBackgroundAndSurface() {
		assertEquals(Color.Black, blackColors().background)
		assertEquals(Color.Black, blackColors().surface)
	}

	@Test
	fun translationsCoverBackendAndWarning() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val results = listOf("en", "ru", "kk").map { language ->
			val configuration = Configuration(context.resources.configuration)
			configuration.setLocale(Locale.forLanguageTag(language))
			val resources = context.createConfigurationContext(configuration).resources
			resources.getString(R.string.backend_unavailable) to resources.getString(R.string.warning_text)
		}
		assertEquals(3, results.map { it.first }.distinct().size)
		assertEquals(3, results.map { it.second }.distinct().size)
		assertNotEquals(results[0], results[1])
	}
}
