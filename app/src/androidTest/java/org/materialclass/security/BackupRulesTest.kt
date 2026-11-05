package org.materialclass.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
	@Test
	fun packagedRulesOnlyIncludeAppearancePreferences() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		for (name in listOf("backup_rules", "data_extraction_rules")) {
			val id = context.resources.getIdentifier(name, "xml", context.packageName)
			val parser = context.resources.getXml(id)
			var includes = 0
			var event = parser.eventType
			while (event != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.START_TAG && parser.name == "include") {
					assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
					assertEquals("appearance.xml", parser.getAttributeValue(null, "path"))
					includes++
				}
				event = parser.next()
			}
			parser.close()
			assertEquals(if (name == "backup_rules") 1 else 2, includes)
		}
	}
}
