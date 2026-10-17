package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The upload ledger's record and a sign-out exclude each other
 * ([AuthTokenManager.runIfSignedIn]): checking the sign-in first and recording after left a gap
 * the sign-out fell into, its clearing of the stamps landing between the two, and the departing
 * account's list stayed for the next account to skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadRecordLockTest {

    @Test
    fun `a sign-out waits for a record under way, and its clear then takes the record with it`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-upload-record-lock-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        val tokenManager = AuthTokenManager(tokens, OkHttpClient(), prefs, "device-1")
        val recordUnderWay = CountDownLatch(1)
        val recordMayEnd = CountDownLatch(1)
        // The courses ledger's store as PushApiClient wires it, with a pause inside the record.
        val store = object : UploadLedgerStore {
            override val fingerprint: String? get() = prefs.coursesUploadFingerprint("1151")
            override val acceptedAtMs: Long get() = prefs.coursesUploadedAtMs("1151")
            override fun record(fingerprint: String, atMs: Long) {
                recordUnderWay.countDown()
                recordMayEnd.await(10, TimeUnit.SECONDS)
                prefs.recordCoursesUpload("1151", fingerprint, atMs)
            }
        }
        val ledger = UploadLedger(store, System::currentTimeMillis)
        val signIn = tokenManager.signInGeneration
        val upload = thread {
            runBlocking {
                ledger.sendIfDue("list-a", ifCurrent = { record -> tokenManager.runIfSignedIn(signIn, record) }) {}
            }
        }
        assertTrue(recordUnderWay.await(10, TimeUnit.SECONDS))

        // AuthService.logout: the generation moves, then the stamps are cleared.
        val signedOut = CountDownLatch(1)
        thread { tokenManager.logout(); signedOut.countDown() }
        assertFalse("the sign-out moved the generation while the record was under way", signedOut.await(300, TimeUnit.MILLISECONDS))
        assertEquals(signIn, tokenManager.signInGeneration)

        recordMayEnd.countDown()
        upload.join(10_000)
        assertTrue(signedOut.await(10, TimeUnit.SECONDS))
        assertNotEquals(signIn, tokenManager.signInGeneration)
        assertEquals("list-a", prefs.coursesUploadFingerprint("1151"))

        prefs.clearSyncStamps()

        assertNull(prefs.coursesUploadFingerprint("1151"))
        assertTrue(runBlocking { ledger.sendIfDue("list-a", ifCurrent = { it() }) {} })
    }
}
