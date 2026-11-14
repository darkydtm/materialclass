package org.materialclass.notifications

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.materialclass.data.Generation

class NotificationStateStoreTest {
	@Test
	fun committedStateSurvivesFreshStoreAndConcurrentUpdates() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val first = SharedPreferencesNotificationStateStore(context)
		first.clear()
		val generation = Generation(42)
		val category = NotificationCategory.NEW_GRADE
		first.reset("synthetic", generation)
		first.markSeen("synthetic", generation, category, "old")
		first.markBaseline("synthetic", generation, category)
		val executor = Executors.newFixedThreadPool(4)
		try {
			val tasks = (0 until 40).map { n -> executor.submit { SharedPreferencesNotificationStateStore(context).markSeen("synthetic", generation, category, "item-$n") } }
			tasks.forEach { it.get(10, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}
		val restored = SharedPreferencesNotificationStateStore(context)
		assertTrue(restored.hasBaseline("synthetic", generation, category))
		assertTrue(restored.hasSeen("synthetic", generation, category, "old"))
		(0 until 40).forEach { assertTrue(restored.hasSeen("synthetic", generation, category, "item-$it")) }
		restored.clear()
		assertFalse(SharedPreferencesNotificationStateStore(context).hasBaseline("synthetic", generation, category))
	}
}
