package org.materialclass.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.app.MaterialClassApplication
import org.materialclass.ui.AppPreferences

@RunWith(AndroidJUnit4::class)
class NotificationWorkManagerTest {
	private lateinit var context: Context
	private lateinit var manager: WorkManager
	private val name = "materialclass-notifications"

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		WorkManagerTestInitHelper.initializeTestWorkManager(context)
		manager = WorkManager.getInstance(context)
		manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
		(context as MaterialClassApplication).runtime
	}

	@Test
	fun periodicSchedulePersistsSelectedIntervalsAndNetworkConstraint() {
		val preferences = AppPreferences(context)
		for (minutes in listOf(15L, 30L, 60L, 120L, 360L)) {
			preferences.setBackgroundInterval(minutes.toFloat())
			NotificationScheduler(context, preferences.backgroundInterval().toLong()).schedule()
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			var info: WorkInfo? = null
			while (System.nanoTime() < deadline) {
				info = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).singleOrNull { !it.state.isFinished }
				if (info?.periodicityInfo?.repeatIntervalMillis == TimeUnit.MINUTES.toMillis(minutes)) break
				Thread.sleep(50)
			}
			assertNotNull(info)
			assertEquals(TimeUnit.MINUTES.toMillis(minutes), info!!.periodicityInfo!!.repeatIntervalMillis)
			assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
		}
	}

	@Test
	fun actualWorkerRunsWithoutSessionAndCancellationStopsPeriodicWork() {
		val runtime = (context as MaterialClassApplication).runtime
		assertEquals(false, runtime.startAuthenticated)
		AppPreferences(context).setBackgroundWork(true)
		NotificationScheduler(context, 30).schedule()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		var info: WorkInfo? = null
		while (info == null && System.nanoTime() < deadline) {
			info = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).singleOrNull { !it.state.isFinished }
			if (info == null) Thread.sleep(50)
		}
		assertNotNull(info)
		val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
		driver.setAllConstraintsMet(info!!.id)
		driver.setPeriodDelayMet(info.id)
		val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
		var completed = false
		while (System.nanoTime() < end) {
			val current = manager.getWorkInfoById(info.id).get(10, TimeUnit.SECONDS)
			if (current.state == WorkInfo.State.ENQUEUED && current.nextScheduleTimeMillis > System.currentTimeMillis()) {
				completed = true
				break
			}
			Thread.sleep(50)
		}
		assertEquals(true, completed)
		AppPreferences(context).setBackgroundWork(false)
		runtime.updateBackgroundSchedule()
		val cancelEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (manager.getWorkInfoById(info.id).get(10, TimeUnit.SECONDS).state != WorkInfo.State.CANCELLED && System.nanoTime() < cancelEnd) Thread.sleep(50)
		assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(info.id).get(10, TimeUnit.SECONDS).state)
	}
}
