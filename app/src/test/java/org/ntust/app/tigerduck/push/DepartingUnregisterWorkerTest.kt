package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** The worker that sends the owed unregister gives up in the end, and drops the debt then. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DepartingUnregisterWorkerTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    private var pending: PendingUnregister? = null
    private val store = object : PendingUnregisterStore {
        override fun load() = pending
        override fun save(pending: PendingUnregister): Boolean {
            this@DepartingUnregisterWorkerTest.pending = pending
            return true
        }
        override fun clear() {
            pending = null
        }
    }

    // Never reaches the server: every DELETE gets no reply.
    private val unregister = DepartingDeviceUnregister(
        owed = OwedUnregister(store),
        deleteDevice = { _, _, _ -> throw IOException("offline") },
        refresh = { _, _ -> DepartingRefresh.Unavailable },
        hasSession = { false },
        currentBaseUrl = { BACKEND },
        now = { 0L },
    )

    private fun worker(attempt: Int) = TestListenableWorkerBuilder<DepartingUnregisterWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                DepartingUnregisterWorker(appContext, workerParameters, unregister)
        })
        .build()

    private fun owe() = runBlocking {
        unregister.owe("device-1", DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, BACKEND))
    }

    @Test
    fun `a try that could not send keeps the debt and tries again`() = runBlocking {
        owe()

        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 3).doWork())
        assertEquals("device-1", pending?.deviceId)
    }

    @Test
    fun `the last try drops the debt rather than keep the tokens for good`() = runBlocking {
        owe()

        assertEquals(ListenableWorker.Result.success(), worker(attempt = DepartingUnregisterWorker.MAX_ATTEMPTS).doWork())
        assertNull(pending)
    }

    private companion object {
        const val BACKEND = "https://backend.example/v3"
    }
}
