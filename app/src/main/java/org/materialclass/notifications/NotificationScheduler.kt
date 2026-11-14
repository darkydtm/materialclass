package org.materialclass.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

interface NotificationWorkScheduler {
	fun schedule()
	fun cancel()
}

object NotificationSchedulePolicy {
	const val REPEAT_INTERVAL_HOURS = 6L
}

class NotificationScheduler(private val context: Context) : NotificationWorkScheduler {
	override fun schedule() {
		val request = PeriodicWorkRequestBuilder<NotificationWorker>(
			NotificationSchedulePolicy.REPEAT_INTERVAL_HOURS,
			TimeUnit.HOURS,
		)
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.build()
		WorkManager.getInstance(context).enqueueUniquePeriodicWork(
			WORK_NAME,
			ExistingPeriodicWorkPolicy.UPDATE,
			request,
		)
	}

	override fun cancel() {
		WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
	}

	private companion object {
		const val WORK_NAME = "materialclass-notifications"
	}
}

class NotificationLifecycle(
	private val scheduler: NotificationWorkScheduler,
	private val publisher: NotificationPublisher,
	private val state: NotificationStateStore? = null,
	private val cancelWorker: () -> Unit = {},
) {
	fun onSessionStarted(preferences: NotificationPreferences) {
		if (preferences.newGradeEnabled || preferences.homeworkEnabled) scheduler.schedule() else scheduler.cancel()
	}

	fun onSettingsChanged(preferences: NotificationPreferences) {
		onSessionStarted(preferences)
	}

	fun onLogout() {
		cancelWorker()
		scheduler.cancel()
		state?.clear()
		publisher.cancelAll()
	}

	fun onExpiration() {
		cancelWorker()
		scheduler.cancel()
		state?.clear()
		publisher.cancelAll()
	}
}

class AndroidNotificationPublisher(private val context: Context) : NotificationPublisher {
	init {
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			val manager = context.getSystemService(NotificationManager::class.java)
			manager.createNotificationChannel(
				NotificationChannel(
					CHANNEL_ID,
					context.getString(org.materialclass.R.string.notification_channel_name),
					NotificationManager.IMPORTANCE_DEFAULT,
				),
			)
		}
	}

	override fun publish(item: NotificationItem) {
		if (android.os.Build.VERSION.SDK_INT >= 33 &&
			context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) return
		val title = when (item.category) {
			NotificationCategory.NEW_GRADE -> context.getString(org.materialclass.R.string.notification_new_grade)
			NotificationCategory.HOMEWORK -> context.getString(org.materialclass.R.string.notification_homework)
		}
		NotificationManagerCompat.from(context).notify(
			item.stableId.hashCode(),
			NotificationCompat.Builder(context, CHANNEL_ID)
				.setSmallIcon(android.R.drawable.ic_dialog_info)
				.setContentTitle(title)
				.setContentText(item.body)
				.setAutoCancel(true)
				.build(),
		)
	}

	override fun cancelAll() {
		NotificationManagerCompat.from(context).cancelAll()
	}

	private companion object {
		const val CHANNEL_ID = "materialclass-updates"
	}
}

class NotificationWorker(
	context: Context,
	params: WorkerParameters,
) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		(applicationContext as org.materialclass.app.MaterialClassApplication).runtime
		val runtime = NotificationWorkerRuntimeRegistry.runtime ?: return Result.failure()
		runtime.run()
		return Result.success()
	}
}
