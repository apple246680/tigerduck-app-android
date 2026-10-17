package org.ntust.app.tigerduck.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A colour or a name set on the class table whose PATCH failed ([PendingCourseOverrides]), over
 * real files (tigerduck-app-android#380). It used to go up once: made offline, the change stayed
 * on this phone alone, never to reach another device, and lost at sign-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingCourseOverridesTest {

    private lateinit var cache: DataCache
    private lateinit var overrides: PendingCourseOverrides

    private data class Patch(
        val moodleId: String,
        val colorHex: String?,
        val customName: String?,
        val locale: String?,
        val account: Int = 1,
    )

    private val patches = mutableListOf<Patch>()
    private var answer: suspend () -> Boolean = { true }

    // AuthTokenManager.signInGeneration, and what happens before the request's header is made.
    private var signIn = 1
    private var beforeHeader: suspend () -> Unit = {}

    private var now = 1_000L

    private val calculus = "1151MA1010002"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        // As PushApiClient.patchCourseOverride: pinned to a sign-in, the header is that account's or none.
        overrides = PendingCourseOverrides(
            cache,
            { moodleId, colorHex, customName, locale, pinned ->
                beforeHeader()
                if (pinned != signIn) throw NotSignedInException()
                patches += Patch(moodleId, colorHex, customName, locale, account = signIn)
                answer()
            },
            signInGeneration = { signIn },
            clock = { now++ },
        )
    }

    @Test
    fun `a colour whose PATCH fails is kept, and the next sync sends it again and lets it go once it lands`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())
        assertEquals(listOf("#FF0000"), cache.loadPendingCourseOverrides().map { it.colorHex })

        answer = { true }
        overrides.retry()

        assertEquals(Patch(calculus, "#FF0000", null, null), patches.last())
        assertFalse(overrides.hasPending())
        assertEquals(emptyList<PendingCourseOverride>(), cache.loadPendingCourseOverrides())
    }

    @Test
    fun `a name whose PATCH the server refuses waits for the next sync`() = runTest {
        answer = { throw org.ntust.app.tigerduck.push.PushApiException("patchCourseOverride failed: HTTP 500", 500) }
        overrides.send(calculus, customName = "Calc", locale = "en")
        overrides.retry()

        assertEquals(2, patches.size)
        assertEquals(listOf("Calc"), cache.loadPendingCourseOverrides().map { it.customName })
    }

    @Test
    fun `a later edit of the same colour or name replaces the one waiting, and the others wait beside it`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")
        overrides.send(calculus, customName = "微積分", locale = "zh-TW")
        overrides.send(calculus, colorHex = "#00FF00")
        overrides.send(calculus, customName = "", locale = "en")

        val waiting = cache.loadPendingCourseOverrides()
        assertEquals(waiting.toString(), 3, waiting.size)
        assertEquals("#00FF00", waiting.single { it.colorHex != null }.colorHex)
        assertEquals("", waiting.single { it.locale == "en" }.customName)
        assertEquals("微積分", waiting.single { it.locale == "zh-TW" }.customName)

        // The older colour and the older name never go up.
        patches.clear()
        answer = { true }
        overrides.retry()
        assertEquals(
            setOf(
                Patch(calculus, "#00FF00", null, null),
                Patch(calculus, null, "", "en"),
                Patch(calculus, null, "微積分", "zh-TW"),
            ),
            patches.toSet(),
        )
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a PATCH that lands after a newer edit of the same colour leaves the newer one waiting`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")

        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            true
        }
        val retry = launch { overrides.retry() }
        sending.await()
        // Picked again while the red was on its way, and offline again by the time this one goes.
        answer = { throw IOException("offline") }
        val again = launch { overrides.send(calculus, colorHex = "#0000FF") }
        runCurrent()
        landed.complete(Unit)
        retry.join()
        again.join()

        assertEquals(listOf("#0000FF"), cache.loadPendingCourseOverrides().map { it.colorHex })
    }

    @Test
    fun `an edit nothing was sent for, the switch being off, is not kept`() = runTest {
        answer = { false }
        overrides.send(calculus, colorHex = "#FF0000")

        assertEquals(1, patches.size)
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `nothing is kept for the next account`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())

        cache.clearAllUserData()

        assertFalse(overrides.hasPending())
        assertEquals(emptyList<PendingCourseOverride>(), cache.loadPendingCourseOverrides())
    }

    @Test
    fun `an edit read as one account is not sent as the next one`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())

        // The account signs out, and another signs in, before the retry's request has its header.
        answer = { true }
        beforeHeader = {
            beforeHeader = {}
            cache.clearAllUserData()
            signIn++
        }
        overrides.retry()

        assertEquals(emptyList<Patch>(), patches.filter { it.account == 2 })
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a snapshot fetched before an edit, or before its PATCH landed, must not take the edit back`() = runTest {
        val colour = PendingCourseOverride.colorKey(calculus)
        answer = { throw IOException("offline") }
        val fetchedBefore = now
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue("held while it waits", colour in overrides.held(fetchedBefore))
        assertTrue("held against a snapshot fetched after the edit too", colour in overrides.held(now))

        answer = { true }
        overrides.retry()
        val landedAt = now
        assertTrue("held against a snapshot fetched before it landed", colour in overrides.held(fetchedBefore))
        assertFalse("but not against one fetched after", colour in overrides.held(landedAt))
    }

    @Test
    fun `a deleted course's waiting colour and name go with it`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")
        overrides.send("1151CS1010001", colorHex = "#00FF00")

        overrides.forget(listOf(calculus))

        assertEquals(listOf("1151CS1010001"), cache.loadPendingCourseOverrides().map { it.moodleId })
    }
}
