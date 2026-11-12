package org.materialclass.notifications

import android.content.Context

/** Store notification preferences without coupling them to account data. */
data class NotificationPreferences(
	val newGradeEnabled: Boolean = false,
	val homeworkEnabled: Boolean = false,
) {
	fun isEnabled(category: NotificationCategory): Boolean = when (category) {
		NotificationCategory.NEW_GRADE -> newGradeEnabled
		NotificationCategory.HOMEWORK -> homeworkEnabled
	}
}

enum class NotificationCategory {
	NEW_GRADE,
	HOMEWORK,
}

interface NotificationSettingsStore {
	fun read(): NotificationPreferences
	fun save(preferences: NotificationPreferences)
}

class SharedPreferencesNotificationSettingsStore(context: Context) : NotificationSettingsStore {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	override fun read(): NotificationPreferences = NotificationPreferences(
		newGradeEnabled = preferences.getBoolean(KEY_NEW_GRADE, false),
		homeworkEnabled = preferences.getBoolean(KEY_HOMEWORK, false),
	)

	override fun save(preferences: NotificationPreferences) {
		this.preferences.edit()
			.putBoolean(KEY_NEW_GRADE, preferences.newGradeEnabled)
			.putBoolean(KEY_HOMEWORK, preferences.homeworkEnabled)
			.apply()
	}

	private companion object {
		const val PREFERENCES_NAME = "notification-settings"
		const val KEY_NEW_GRADE = "new-grade-enabled"
		const val KEY_HOMEWORK = "homework-enabled"
	}
}

class InMemoryNotificationSettingsStore : NotificationSettingsStore {
	private var value = NotificationPreferences()

	override fun read(): NotificationPreferences = value

	override fun save(preferences: NotificationPreferences) {
		value = preferences
	}
}
