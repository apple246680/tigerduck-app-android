package org.ntust.app.tigerduck.auth

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What follows the school account's sign-in and sign-out, whichever page they came from: the
 * onboarding wizard and Settings both sign in through AuthService, and the effects follow its
 * signed-in state rather than either page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AccountSessionEffectsTest {

    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val signedIn = MutableStateFlow(false)
    private val liveUpdates = mutableListOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun effects(startSignedIn: Boolean): AccountSessionEffects {
        signedIn.value = startSignedIn
        return AccountSessionEffects(
            context = context,
            authState = signedIn,
            refreshLiveUpdates = { liveUpdates += "refresh" },
            stopLiveUpdates = { liveUpdates += "stop" },
        ).also { it.start(scope) }
    }

    private fun backgroundSync(): List<WorkInfo.State> =
        workManager.getWorkInfosForUniqueWork(BackgroundSyncWorker.UNIQUE_NAME).get().map { it.state }

    @Test
    fun `a sign-in schedules the background sync, as the onboarding wizard's does`() {
        // A fresh install: nobody is signed in when the app starts.
        effects(startSignedIn = false)
        assertEquals(emptyList<WorkInfo.State>(), backgroundSync())

        signedIn.value = true

        assertEquals(listOf(WorkInfo.State.ENQUEUED), backgroundSync())
    }

    @Test
    fun `a sign-out cancels the background sync`() {
        effects(startSignedIn = true)
        BackgroundSyncWorker.schedule(context)

        signedIn.value = false

        assertEquals(listOf(WorkInfo.State.CANCELLED), backgroundSync())
    }

    @Test
    fun `the state the app starts in is not a sign-in`() {
        // MainActivity schedules for an account signed in at launch; a launch is not a change.
        effects(startSignedIn = true)

        assertEquals(emptyList<WorkInfo.State>(), backgroundSync())
    }

    @Test
    fun `a sign-out stops the Live Update and a sign-in brings it back`() {
        effects(startSignedIn = true)

        signedIn.value = false
        signedIn.value = true

        assertEquals(listOf("stop", "refresh"), liveUpdates)
    }
}
