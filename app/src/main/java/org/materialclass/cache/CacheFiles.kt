package org.materialclass.cache

import android.content.Context
import android.system.Os
import java.io.File

internal class CacheFiles(context: Context) {
	val directory = File(context.filesDir, "backup-cache").apply { check(mkdirs() || isDirectory) }
	private val temporary = File(context.noBackupFilesDir, "storage-tmp").apply { check(mkdirs() || isDirectory) }

	fun replace(target: File, bytes: ByteArray) {
		val temp = File.createTempFile("encrypted-", ".tmp", temporary)
		try {
			temp.outputStream().use { it.write(bytes); it.fd.sync() }
			Os.rename(temp.absolutePath, target.absolutePath)
		} finally {
			if (temp.exists()) temp.delete()
		}
	}
}
