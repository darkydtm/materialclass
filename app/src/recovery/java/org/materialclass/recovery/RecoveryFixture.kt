package org.materialclass.recovery

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Process
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.materialclass.notifications.NotificationScheduler
import org.materialclass.notifications.NotificationWorker

class RecoveryInitializer : ContentProvider() {
	override fun onCreate(): Boolean {
		val app = context!!.applicationContext
		WorkManager.initialize(app, Configuration.Builder().setWorkerFactory(RecoveryFactory()).build())
		return true
	}
	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

class RecoveryFactory : WorkerFactory() {
	override fun createWorker(context: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
		if (workerClassName != NotificationWorker::class.java.name) return null
		return RecoveryWorker(context, workerParameters)
	}
}

class RecoveryWorker(context: Context, private val parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		val result = NotificationWorker(applicationContext, parameters).doWork()
		applicationContext.openFileOutput("recovery-marker", Context.MODE_APPEND).bufferedWriter().use {
			it.write("${Process.myPid()}|$id|${result == Result.success()}\n")
		}
		return result
	}
}

class RecoveryReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val pending = goAsync()
		Thread {
			try {
				NotificationScheduler(context, 15).schedule()
				val manager = WorkManager.getInstance(context)
				val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
				while (System.nanoTime() < deadline) {
					val info = manager.getWorkInfosForUniqueWork("materialclass-notifications").get(5, TimeUnit.SECONDS).singleOrNull()
					if (info != null) {
						context.openFileOutput("recovery-seed", Context.MODE_PRIVATE).bufferedWriter().use {
							it.write("${Process.myPid()}|${info.id}|${info.state}\n")
						}
						return@Thread
					}
					Thread.sleep(100)
				}
			} finally {
				pending.finish()
			}
		}.start()
	}
}
