package org.materialclass.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import org.materialclass.api.GatewayLoginResult
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

class MaterialClassNavigationTest {
	@get:Rule
	val composeRule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun loginFormRemainsAccessibleInShortWindow() {
		composeRule.setContent {
			Box(Modifier.height(240.dp)) {
				MaterialClassApp()
			}
		}
		composeRule.onNodeWithTag("login-input").performScrollTo().assertIsDisplayed()
		composeRule.onNodeWithTag("password-input").performScrollTo().assertIsDisplayed()
		composeRule.onNodeWithTag("login-button").performScrollTo().assertIsDisplayed()
	}

	@Test
	fun scheduleKeepsSelectedDayAcrossTabsWithRealScheduleDays() {
		val schedule = org.materialclass.data.ScheduleModel(5, 5, listOf(
			org.materialclass.data.ScheduleDay("Monday", false, false, listOf(org.materialclass.data.ScheduleLesson("monday", "Math", "09:00", "101", null, null, null, null))),
			org.materialclass.data.ScheduleDay("Tuesday", false, false, listOf(org.materialclass.data.ScheduleLesson("tuesday", "Physics", "10:00", "102", null, null, null, null))),
			org.materialclass.data.ScheduleDay("Wednesday", false, false, listOf(org.materialclass.data.ScheduleLesson("wednesday", "Chemistry", "11:00", "103", null, null, null, null))),
			org.materialclass.data.ScheduleDay("Thursday", false, false, listOf(org.materialclass.data.ScheduleLesson("thursday", "History", "12:00", "104", null, null, null, null))),
			org.materialclass.data.ScheduleDay("Friday", false, false, listOf(org.materialclass.data.ScheduleLesson("friday", "Biology", "13:00", "105", null, null, null, null))),
		))
		val repository = object : org.materialclass.data.SchoolRepository {
			override fun observeHome() = org.materialclass.data.RepositoryObservation<org.materialclass.data.HomeModel>(org.materialclass.data.DataState.Loading)
			override fun observeDiary(range: org.materialclass.data.DateRange) = org.materialclass.data.RepositoryObservation<org.materialclass.data.DiaryModel>(org.materialclass.data.DataState.Loading)
			override fun observeSchedule(range: org.materialclass.data.DateRange) = org.materialclass.data.RepositoryObservation<org.materialclass.data.ScheduleModel>(org.materialclass.data.DataState.Ready(org.materialclass.data.Snapshot(org.materialclass.data.AccountId("test"), org.materialclass.data.Generation(1), schedule, java.time.Instant.EPOCH)))
			override fun observeGrades(period: String) = org.materialclass.data.RepositoryObservation<org.materialclass.data.GradesModel>(org.materialclass.data.DataState.Loading)
			override fun observeHomeworks() = org.materialclass.data.RepositoryObservation<org.materialclass.data.HomeworksModel>(org.materialclass.data.DataState.Loading)
			override fun observeJournal(range: org.materialclass.data.DateRange) = org.materialclass.data.RepositoryObservation<org.materialclass.data.JournalGradesModel>(org.materialclass.data.DataState.Loading)
			override fun observeProfile() = org.materialclass.data.RepositoryObservation<org.materialclass.data.ProfileModel>(org.materialclass.data.DataState.Loading)
			override fun refreshHome() = Unit
			override fun refreshDiary(range: org.materialclass.data.DateRange) = Unit
			override fun refreshSchedule(range: org.materialclass.data.DateRange) = Unit
			override fun refreshGrades(period: String) = Unit
			override fun refreshHomeworks() = Unit
			override fun refreshJournal(range: org.materialclass.data.DateRange) = Unit
			override fun refreshProfile() = Unit
			override fun logout() = Unit
		}
		composeRule.setContent { MaterialClassApp(startAuthenticated = true, repository = repository) }
		composeRule.onNodeWithTag("tab-schedule").performClick()
		composeRule.onNodeWithTag("schedule-day-2").performClick()
		composeRule.onNodeWithText("Chemistry").assertIsDisplayed()
		composeRule.onNodeWithTag("tab-home").performClick()
		composeRule.onNodeWithTag("tab-schedule").performClick()
		composeRule.onNodeWithTag("schedule-day-2").assertIsSelected()
		composeRule.onNodeWithText("Chemistry").assertIsDisplayed()
	}

	@Test
	fun pullRefreshCancelsBelowThresholdAndWaitsForRealRequest() {
		val started = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		val calls = AtomicInteger()
		composeRule.setContent {
			org.materialclass.ui.theme.MaterialClassTheme(ThemeMode.BLACK) {
				PullRefreshLayout(Modifier.fillMaxSize().testTag("pull-refresh-root"), onRefresh = {
					calls.incrementAndGet()
					started.complete(Unit)
					finish.await()
				}) { modifier ->
					Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("pull-refresh-content")) {
						Spacer(Modifier.height(1_000.dp))
					}
				}
			}
		}
		val root = composeRule.onNodeWithTag("pull-refresh-root")
		root.performTouchInput { down(center); moveTo(center.copy(y = center.y + 30.dp.toPx())); up() }
		composeRule.runOnIdle { check(calls.get() == 0) }
		root.performTouchInput {
			down(center)
			for (step in 1..4) moveTo(center.copy(y = center.y + step * 30.dp.toPx()))
		}
		composeRule.onNodeWithTag("pull-refresh-background").assertIsDisplayed()
		val dragIndicatorBounds = composeRule.onNodeWithTag("pull-refresh-indicator").getUnclippedBoundsInRoot()
		val dragBackgroundBounds = composeRule.onNodeWithTag("pull-refresh-background").getUnclippedBoundsInRoot()
		check(kotlin.math.abs(
			(dragIndicatorBounds.top.value + dragIndicatorBounds.bottom.value - dragBackgroundBounds.top.value - dragBackgroundBounds.bottom.value) / 2f,
		) < 1f) {
			"Background and indicator must share their center while dragging"
		}
		root.performTouchInput { up() }
		composeRule.runOnIdle { check(calls.get() == 0) }
		root.performTouchInput {
			down(center)
			for (step in 1..8) moveTo(center.copy(y = center.y + step * 30.dp.toPx()))
			up()
		}
		composeRule.waitUntil(5_000) { started.isCompleted }
		composeRule.onNodeWithTag("pull-refresh-background").assertIsDisplayed()
		val loadingIndicatorBounds = composeRule.onNodeWithTag("pull-refresh-indicator").getUnclippedBoundsInRoot()
		val loadingBackgroundBounds = composeRule.onNodeWithTag("pull-refresh-background").getUnclippedBoundsInRoot()
		check(kotlin.math.abs(
			(loadingIndicatorBounds.top.value + loadingIndicatorBounds.bottom.value - loadingBackgroundBounds.top.value - loadingBackgroundBounds.bottom.value) / 2f,
		) < 1f) {
			"Background and indicator must share their center while refreshing"
		}
		composeRule.runOnIdle { check(calls.get() == 1) { "Threshold release must start exactly one refresh" } }
		root.performTouchInput {
			down(center)
			for (step in 1..8) moveTo(center.copy(y = center.y + step * 30.dp.toPx()))
			up()
		}
		composeRule.runOnIdle { check(calls.get() == 1) }
		finish.complete(Unit)
		composeRule.waitForIdle()
		composeRule.onAllNodesWithTag("pull-refresh-background").assertCountEquals(0)
	}

	@Test
	fun holdingSettingsOpensDebugScreen() {
		composeRule.setContent { MaterialClassApp(startAuthenticated = true) }
		val settings = composeRule.activity.getString(org.materialclass.R.string.nav_settings)
		val debug = composeRule.activity.getString(org.materialclass.R.string.debug_title)
		composeRule.onNodeWithText(settings).performTouchInput {
			down(center)
			advanceEventTime(3100)
			up()
		}
		composeRule.onNodeWithText(debug).assertIsDisplayed()
		composeRule.onNodeWithText(
			"Date: ${org.materialclass.BuildConfig.BUILD_DATE}\nHash: ${org.materialclass.BuildConfig.BUILD_REVISION}\nBranch: ${org.materialclass.BuildConfig.BUILD_BRANCH}",
		).assertIsDisplayed()
	}

	@Test
	fun completedEdgeSwipeReturnsToPreviousScreen() {
		lateinit var layout: PredictiveBackLayout
		var closes = 0
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
						{ Box(Modifier.fillMaxSize().background(Color.Blue)) },
						{ Box(Modifier.fillMaxSize().background(Color.Yellow)) },
					) { closes++ }
				},
			)
		}
		composeRule.waitForIdle()
		val position = IntArray(2)
		composeRule.runOnUiThread { layout.getLocationOnScreen(position) }
		val startX = position[0] + 10f
		val y = position[1] + layout.height / 2f
		val downTime = SystemClock.uptimeMillis()
		fun send(action: Int, x: Float) {
			val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
			try {
				InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
			} finally {
				event.recycle()
			}
		}
		send(MotionEvent.ACTION_DOWN, startX)
		for (step in 1..10) {
			SystemClock.sleep(20)
			send(MotionEvent.ACTION_MOVE, startX + layout.width * 0.6f * step / 10)
		}
		send(MotionEvent.ACTION_UP, startX + layout.width * 0.6f)
		composeRule.waitUntil(5_000) { closes == 1 }
		composeRule.runOnUiThread {
			layout.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null,
				{ Box(Modifier.fillMaxSize().background(Color.Blue)) }) { closes++ }
		}
		composeRule.waitForIdle()
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		check(pixels[pixels.width / 2, pixels.height / 2] == Color.Blue)
		check(closes == 1)
	}

	@Test
	fun cancelledEdgeSwipeKeepsDetailAndShowsPreviousDuringDrag() {
		lateinit var layout: PredictiveBackLayout
		var closes = 0
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
						{ Box(Modifier.fillMaxSize().background(Color.Blue)) },
						{ Box(Modifier.fillMaxSize().background(Color.Yellow)) },
					) { closes++ }
				},
			)
		}
		composeRule.waitForIdle()
		val position = IntArray(2)
		composeRule.runOnUiThread { layout.getLocationOnScreen(position) }
		val startX = position[0] + 10f
		val y = position[1] + layout.height / 2f
		val downTime = SystemClock.uptimeMillis()
		fun send(action: Int, x: Float) {
			val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
			try {
				InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
			} finally {
				event.recycle()
			}
		}
		send(MotionEvent.ACTION_DOWN, startX)
		for (step in 1..10) {
			SystemClock.sleep(20)
			send(MotionEvent.ACTION_MOVE, startX + layout.width * 0.6f * step / 10)
		}
		composeRule.waitForIdle()
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		check(pixels[pixels.width / 4, pixels.height / 2] == Color.Blue) { "Swipe must reveal the previous screen" }
		check(pixels[pixels.width * 3 / 4, pixels.height / 2] == Color.Yellow) { "Swipe must retain the detail screen" }
		send(MotionEvent.ACTION_CANCEL, startX + layout.width * 0.6f)
		composeRule.waitUntil(5_000) { layout.getChildAt(3).left == 0 }
		composeRule.runOnIdle { check(closes == 0) { "Cancelled swipe closed the detail screen" } }
	}

	@Test
	fun predictiveProgressRevealsThePreviousScreenWithoutTintingIt() {
		lateinit var layout: PredictiveBackLayout
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize().testTag("back-layout"),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						backgroundColor = android.graphics.Color.BLACK,
						gestureEnabled = true,
						previousRoute = AppRoute.Settings,
						currentRoute = AppRoute.SettingsTheme,
						previousContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
						currentContent = { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
						onClose = {},
					)
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread { layout.setPredictiveProgress(0.5f) }
		composeRule.waitForIdle()
		val pixels = composeRule.onNodeWithTag("back-layout").captureToImage().toPixelMap()
		check(pixels[pixels.width / 4, pixels.height / 2] == Color.Blue)
		check(pixels[pixels.width * 3 / 4, pixels.height / 2] == Color.Yellow)
	}

	@Test
	fun nonPredictiveSystemBackDoesNotPopBeforeClosingAnimation() {
		lateinit var layout: PredictiveBackLayout
		var closes = 0
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
						{ Box(Modifier.fillMaxSize().background(Color.Blue)) },
						{ Box(Modifier.fillMaxSize().background(Color.Yellow)) }) { closes++ }
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread {
			layout.commitPredictiveBack()
			check(closes == 0) { "Back popped immediately instead of animating the outgoing screen" }
		}
		composeRule.waitUntil(5_000) { closes == 1 }
	}

	@Test
	fun openingSecondaryScreenStartsAtRightEdge() {
		lateinit var layout: PredictiveBackLayout
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(
						backgroundColor = android.graphics.Color.BLACK,
						gestureEnabled = false,
						previousRoute = null,
						currentRoute = AppRoute.Settings,
						previousContent = null,
						currentContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
						onClose = {},
					)
				},
			)
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread {
			layout.updateLayers(
				backgroundColor = android.graphics.Color.BLACK,
				gestureEnabled = true,
				previousRoute = AppRoute.Settings,
				currentRoute = AppRoute.SettingsTheme,
				previousContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
				currentContent = { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
				onClose = {},
			)
			check(layout.width > 0)
			check(layout.getChildAt(3).left == layout.width) { "Opening screen must start at the right edge" }
		}
	}

	@Test
	fun previousScreenEffectDoesNotRestartWhenOpeningDetail() {
		lateinit var layout: PredictiveBackLayout
		var loads = 0
		val previous: @Composable () -> Unit = {
			LaunchedEffect(Unit) { loads++ }
			Box(Modifier.fillMaxSize().background(Color.Blue))
		}
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
				},
			)
		}
		composeRule.waitForIdle()
		check(loads == 1)
		composeRule.runOnUiThread {
			layout.updateLayers(
				android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
				previous, { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
			) { }
		}
		composeRule.waitForIdle()
		check(loads == 1) { "Opening detail restarted the previous screen effect: $loads" }
	}

	@Test
	fun returningToPreviousScreenDoesNotRestartItsEffect() {
		lateinit var layout: PredictiveBackLayout
		var loads = 0
		val previous: @Composable () -> Unit = {
			LaunchedEffect(Unit) { loads++ }
			Box(Modifier.fillMaxSize().background(Color.Blue))
		}
		composeRule.setContent {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { PredictiveBackLayout(it).also { view -> layout = view } },
				update = { view ->
					view.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
				},
			)
		}
		composeRule.waitForIdle()
		var closes = 0
		composeRule.runOnUiThread {
			layout.updateLayers(
				android.graphics.Color.BLACK, true, AppRoute.Settings, AppRoute.SettingsTheme,
				previous, { Box(Modifier.fillMaxSize().background(Color.Yellow)) },
			) { closes++ }
		}
		composeRule.waitForIdle()
		composeRule.runOnUiThread { layout.commitPredictiveBack() }
		composeRule.waitUntil(5_000) { closes == 1 }
		composeRule.runOnUiThread {
			layout.updateLayers(android.graphics.Color.BLACK, false, null, AppRoute.Settings, null, previous) { }
		}
		composeRule.waitForIdle()
		check(loads == 1) { "Returning to previous screen restarted its effect: $loads" }
	}

	@Test
	fun actualBackButtonReturnsFromThemeToSettings() {
		composeRule.setContent { MaterialClassApp(startAuthenticated = true) }
		val context = composeRule.activity
		val settings = context.getString(org.materialclass.R.string.nav_settings)
		val theme = context.getString(org.materialclass.R.string.theme)
		composeRule.onNodeWithText(settings).performClick()
		composeRule.onNodeWithText(theme).performClick()
		composeRule.onNodeWithTag("back").performClick()
		composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("back").fetchSemanticsNodes().isEmpty() }
		composeRule.onNodeWithText(theme).assertIsDisplayed()
	}

	@Test
	fun selectingPrimaryRouteClearsSecondaryHistory() {
		val navigator = AppNavigator(AppRoute.Settings)
		navigator.go(AppRoute.SettingsTheme)
		navigator.go(AppRoute.Schedule)
		check(navigator.current == AppRoute.Schedule)
		check(!navigator.canGoBack())
		check(navigator.previousOrNull() == null)
		check(navigator.handlePrimaryBack())
		check(navigator.current == AppRoute.Home)
	}

	@Test
	fun loginFailureShowsLocalizedValidation() {
		composeRule.activity.setContent {
			MaterialClassApp(loginHandler = { GatewayLoginResult.Failure(org.materialclass.api.AppError.InvalidCredentials) })
		}
		composeRule.onNodeWithTag("login-button").performClick()
		composeRule.onNodeWithText("Проверьте логин и пароль").assertIsDisplayed()
	}

	@Test
	fun routeNavigatorBackReturnsToParent() {
		val navigator = AppNavigator(AppRoute.Home)
		navigator.go(AppRoute.Schedule)
		navigator.go(AppRoute.LessonDetail)
		check(navigator.back())
		check(navigator.current == AppRoute.Schedule)
	}

	@Test
	fun dayProgressCardFillsThePaddedHomeWidth() {
		composeRule.activity.setContent {
			org.materialclass.ui.theme.MaterialClassTheme(ThemeMode.BLACK) {
				HomeScreen(PaddingValues(0.dp), { it }, SchoolViewModel(), AppNavigator(AppRoute.Home)) { }
			}
		}
		val home = composeRule.onNodeWithTag("home-screen").getUnclippedBoundsInRoot()
		val card = composeRule.onNodeWithTag("day-progress-card").getUnclippedBoundsInRoot()
		check(card.left == home.left + 20.dp)
		check(card.right == home.right - 20.dp)
	}
}