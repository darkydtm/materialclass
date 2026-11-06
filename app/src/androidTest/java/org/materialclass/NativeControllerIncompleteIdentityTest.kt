package org.materialclass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.api.*
import org.materialclass.data.*
import org.materialclass.session.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeControllerIncompleteIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val coordinator = SessionCoordinator(context)
    private val controllers = mutableListOf<NativeController>()
    private var verification: (UnverifiedSession) -> SessionVerification = ::completeVerification

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for controller state")
    }

    private fun completeVerification(session: UnverifiedSession): SessionVerification = verified(session, complete = true)

    private fun verified(session: UnverifiedSession, complete: Boolean): SessionVerification.Verified {
        val id = requireNotNull(session.identityCandidate)
        if (!complete) return SessionVerification.Verified(AccountId(id), true)
        val profile = JSONObject(
            """{"userId":$id,"role":"student","school_id":1,"group":{"id":2},"studentInfo":{"studentSchoolUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},"school":{"name":"Synthetic School","eduYears":[{"isCurrent":true,"eduYear":2026}]}}"""
        )
        return SessionVerification.Verified(
            AccountId(id),
            true,
            scheduleSession = VerifiedScheduleSession.compare(session, profile),
            diarySession = VerifiedDiarySession.compare(session, profile),
            profile = StudentProfile("Synthetic Surname", "Synthetic Firstname", "Synthetic School", "Synthetic Group"),
        )
    }

    private fun createController(
        gate: AtomicReference<CountDownLatch?> = AtomicReference(null),
        entered: CountDownLatch? = null,
    ): NativeController {
        val gateway = object : SchoolGateway {
            override fun login(login: String, password: CharArray, lease: RequestLease): GatewayResult<UnverifiedSession> {
                entered?.countDown()
                gate.getAndSet(null)?.let { blocked -> check(blocked.await(15, TimeUnit.SECONDS)) }
                return GatewayResult.Success(
                    UnverifiedSession(
                        "synthetic-token-$login",
                        login,
                        "student",
                        SchoolContext(1, 2026, 2, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                    )
                )
            }

            override fun currentWeek(session: VerifiedDiarySession, lease: RequestLease): GatewayResult<String> = GatewayResult.Success("01.01.2026")
            override fun diary(session: VerifiedDiarySession, context: DiaryContext, lease: RequestLease): GatewayResult<Diary> = GatewayResult.Success(Diary(context.date, emptyList()))
            override fun schedule(session: VerifiedScheduleSession, date: String, lease: RequestLease): GatewayResult<Schedule> = GatewayResult.Success(Schedule(emptyList()))
        }
        val cache = object : DiaryCache {
            override fun read(session: ActiveSession): ByteArray? = null
            override fun write(session: ActiveSession, bytes: ByteArray): Boolean = true
        }
        return NativeController { emit ->
            SchoolRepository(gateway, IdentityVerifier { session, _ -> verification(session) }, coordinator, cache, { 1L }, emit, SessionStore(context, coordinator))
        }.also { controllers += it }
    }

    private fun login(controller: NativeController, account: String) {
        main { controller.login(account, charArrayOf('x')) }
        await { !controller.state.busy }
    }

    private fun resetActiveForRestore() {
        SessionCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true; set(null, null) }
        SessionCoordinator::class.java.getDeclaredField("restorationAllowed").apply { isAccessible = true; setBoolean(null, true) }
    }

    @After
    fun cleanup() {
        main { controllers.forEach(NativeController::close) }
        coordinator.logout()
        File(context.noBackupFilesDir, "session").delete()
    }

    @Test
    fun incompleteLoginDoesNotActivatePersistOrPublishSignedIn() {
        verification = { session -> verified(session, complete = false) }
        val controller = createController()
        login(controller, "1")
        main {
            assertFalse(controller.state.signedIn)
            assertNull(controller.state.profile)
            assertFalse(SessionCoordinator.privateStates.read().states.values.any { it.signedIn })
        }
        assertFalse(File(context.noBackupFilesDir, "session").exists())
        assertNull(coordinator.event().session)
    }

    @Test
    fun completeLoginActivatesPersistsAndPublishesProfile() {
        verification = ::completeVerification
        val controller = createController()
        login(controller, "1")
        main {
            assertTrue(controller.state.signedIn)
            assertNotNull(controller.state.profile)
        }
        assertTrue(File(context.noBackupFilesDir, "session").exists())
        assertEquals(AccountId("1"), coordinator.event().session?.account)
    }

    @Test
    fun incompleteRestoreRevokesStoredSessionWithoutPublishingSignedIn() {
        verification = ::completeVerification
        val seed = createController()
        login(seed, "1")
        seed.close()
        resetActiveForRestore()
        verification = { session -> verified(session, complete = false) }
        val controller = createController()
        main { controller.start() }
        await { !controller.state.busy || coordinator.pendingLogoutTicket() != null }
        main {
            assertFalse(controller.state.signedIn)
            assertNull(controller.state.profile)
            assertFalse(SessionCoordinator.privateStates.read().states.values.any { it.signedIn })
        }
        assertFalse(File(context.noBackupFilesDir, "session").exists())
        assertTrue(coordinator.event().session == null || coordinator.pendingLogoutTicket() != null)
    }

    @Test
    fun completeRestorePublishesVerifiedProfile() {
        verification = ::completeVerification
        val seed = createController()
        login(seed, "1")
        seed.close()
        resetActiveForRestore()
        val controller = createController()
        main { controller.start() }
        await { !controller.state.busy }
        main {
            assertTrue(controller.state.signedIn)
            assertEquals("Synthetic Surname", controller.state.profile?.surname)
        }
        assertEquals(AccountId("1"), coordinator.event().session?.account)
    }

    @Test
    fun staleAccountAResultCannotRevokeLaterAccountAAfterB() {
        verification = ::completeVerification
        val firstGate = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val first = createController(AtomicReference<CountDownLatch?>(firstGate), firstEntered)
        val second = createController()
        val third = createController()
        main { first.login("1", charArrayOf('x')) }
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        login(second, "2")
        login(third, "1")
        firstGate.countDown()
        await { !first.state.busy }
        main {
            assertTrue(third.state.signedIn)
            assertEquals("Synthetic Surname", third.state.profile?.surname)
        }
        assertEquals(AccountId("1"), coordinator.event().session?.account)
        assertFalse(first.state.signedIn)
    }
}
