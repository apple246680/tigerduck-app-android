package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bulletin page's "Last synced" row: when a refresh last brought the list from the server,
 * kept with the cached list and cleared with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsSyncedAtTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val cache = BulletinCache(context)

    /** Whether the backend answers the list; the taxonomy it never answers, as the page allows. */
    @Volatile private var listAnswers = true

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val ok = listAnswers && request.url.encodedPath.endsWith("/bulletins")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (ok) 200 else 503)
                .message(if (ok) "OK" else "Unavailable")
                .body((if (ok) LIST else "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun viewModel(): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            api, cache, BulletinRepository(), BulletinReadStateStore(context), testDemoAccount(context, prefs, cache),
        )
    }

    /** The list's IO runs on real threads, so this waits for it rather than for a test clock. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a refresh that lands dates the list, and the date outlives the page`() {
        val before = System.currentTimeMillis()
        val vm = viewModel()
        vm.load()
        eventually("the first refresh is saved") { runBlocking { cache.loadFetchedAt() } != 0L }
        val fetched = runBlocking { cache.loadFetchedAt() }
        assertTrue("$fetched is not this refresh's time", fetched in before..System.currentTimeMillis())
        assertEquals(fetched, vm.state.value.syncedAtMs)

        // The next visit opens on the cached list and its date; a refresh that fails then
        // leaves both.
        listAnswers = false
        val next = viewModel()
        next.load()
        eventually("the next visit's refresh fails") { next.state.value.loadState is LoadState.Failed }
        assertEquals(fetched, next.state.value.syncedAtMs)
        assertEquals(fetched, runBlocking { cache.loadFetchedAt() })
    }

    @Test
    fun `a failed refresh leaves the date where it was`() {
        val vm = viewModel()
        vm.load()
        eventually("the first refresh is saved") { runBlocking { cache.loadFetchedAt() } != 0L }
        val fetched = vm.state.value.syncedAtMs
        assertTrue(fetched > 0L)

        listAnswers = false
        vm.refresh()
        eventually("the refresh fails") { vm.state.value.loadState is LoadState.Failed }

        assertEquals(fetched, vm.state.value.syncedAtMs)
        assertEquals(fetched, runBlocking { cache.loadFetchedAt() })
    }

    /** What the school sign-out does to the bulletins: the list goes, and its date with it. */
    @Test
    fun `the date goes with the cached list, and only with it`() = runBlocking {
        cache.saveFetchedAt(5_000L)
        cache.save(emptyList())
        assertEquals(5_000L, cache.loadFetchedAt())

        cache.clear()

        assertEquals(0L, cache.loadFetchedAt())
    }

    private companion object {
        const val LIST = """{"items":[{"id":1,"external_id":"e1","title":"Bulletin","posted_at":"2026-10-01T00:00:00Z"}],"next_cursor":null}"""
    }
}
