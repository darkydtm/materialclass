package org.materialclass.security

import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Printer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
	@Test
	fun packagedRulesOnlyIncludeAppearancePreferences() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val info = context.packageManager.getApplicationInfo(context.packageName, 0)
		assertEquals(ApplicationInfo.FLAG_ALLOW_BACKUP, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
		assertNull(info.backupAgentName)
		val dump = mutableListOf<String>()
		info.dump(Printer { dump.add(it) }, "")
		val names = if (Build.VERSION.SDK_INT >= 31) {
			listOf("fullBackupContent" to "backup_rules", "dataExtractionRules" to "data_extraction_rules")
		} else {
			listOf("fullBackupContent" to "backup_rules")
		}
		for ((attribute, name) in names) {
			val id = context.resources.getIdentifier(name, "xml", context.packageName)
			val bound = dump.filter { it.startsWith("$attribute=") }
			assertEquals(listOf("$attribute=@xml/$id"), bound)
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
