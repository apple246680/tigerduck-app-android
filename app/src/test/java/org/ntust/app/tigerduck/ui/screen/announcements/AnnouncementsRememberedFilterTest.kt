package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.OrgLabel
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Remember bulletin filter": the saved departments go on only once the department chips can
 * show them, only those the department list still has, and never over a pick made on the visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsRememberedFilterTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply {
        announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        rememberAnnouncementFilter = true
        savedAnnouncementOrgs = setOf("library", "gone")
    }
    private val cache = BulletinCache(context)
    private val repository = BulletinRepository()

    /** Whether the backend answers the department list; it waits on [taxonomyGate] first. */
    @Volatile private var taxonomyAnswers = true
    private val taxonomyGate = CountDownLatch(1)
    private val taxonomyRequests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/bulletins") -> LIST
                path.endsWith("/bulletins/taxonomy") -> {
                    taxonomyRequests.incrementAndGet()
                    taxonomyGate.await(5, TimeUnit.SECONDS)
                    TAXONOMY.takeIf { taxonomyAnswers }
                }
                else -> null
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 503)
                .message(if (body != null) "OK" else "Unavailable")
                .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @After fun openGate() = taxonomyGate.countDown()

    private fun viewModel(): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            api, cache, repository, BulletinReadStateStore(context), prefs, testDemoAccount(context, prefs, cache),
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

    private fun AnnouncementsViewModel.shownIds() = state.value.displayed.map { it.id }.toSet()

    @Test
    fun `the remembered departments wait for the department list, and only those it has return`() {
        val vm = viewModel()
        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        vm.load()
        eventually("the list loads") { vm.state.value.loadState == LoadState.Loaded }
        eventually("the department list is asked for") { taxonomyRequests.get() > 0 }
        // No chips on screen yet, so nothing is filtered by them.
        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        assertEquals(setOf(1, 2), vm.shownIds())

        taxonomyGate.countDown()
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        assertEquals(setOf("library"), vm.state.value.selectedOrgs)
        assertEquals(setOf(1), vm.shownIds())
    }

    @Test
    fun `without the department list the list stays unfiltered`() {
        taxonomyAnswers = false
        taxonomyGate.countDown()
        val vm = viewModel()
        vm.load()
        eventually("the list loads") { vm.state.value.loadState == LoadState.Loaded }
        eventually("the department list is asked for") { taxonomyRequests.get() > 0 }
        Thread.sleep(200)

        assertEquals(null, vm.state.value.taxonomy)
        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        assertEquals(setOf(1, 2), vm.shownIds())
        // Still saved for a visit that does get the list.
        assertEquals(setOf("library", "gone"), prefs.savedAnnouncementOrgs)
    }

    @Test
    fun `a department picked on this visit is not replaced by the remembered ones`() {
        val vm = viewModel()
        vm.load()
        eventually("the department list is asked for") { taxonomyRequests.get() > 0 }
        vm.setOrgFilter(setOf("registrar"))

        taxonomyGate.countDown()
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        assertEquals(setOf("registrar"), vm.state.value.selectedOrgs)
    }

    @Test
    fun `a return visit with the department list in memory opens on the remembered departments`() {
        runBlocking { repository.getOrFetchTaxonomy { TAXONOMY_VALUE } }
        runBlocking { cache.save(listOf(bulletin(1, "library"), bulletin(2, "registrar"))) }

        val vm = viewModel()

        assertEquals(setOf("library"), vm.state.value.selectedOrgs)
        vm.load()
        eventually("the cached list shows") { vm.state.value.items.isNotEmpty() }
        assertEquals(setOf(1), vm.shownIds())
    }

    @Test
    fun `with the setting off nothing is restored`() {
        prefs.rememberAnnouncementFilter = false
        taxonomyGate.countDown()
        val vm = viewModel()
        vm.load()
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
    }

    private fun bulletin(id: Int, org: String) = BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = org,
        importance = null, summary = null, sourceUrl = null, postedAt = "2026-10-0${id}T00:00:00Z",
    )

    private companion object {
        const val LIST = """{"items":[""" +
            """{"id":1,"external_id":"e1","title":"One","canonical_org":"library","content_tags":[],"posted_at":"2026-10-01T00:00:00Z"},""" +
            """{"id":2,"external_id":"e2","title":"Two","canonical_org":"registrar","content_tags":[],"posted_at":"2026-10-02T00:00:00Z"}""" +
            """],"next_cursor":null}"""
        const val TAXONOMY = """{"orgs":[{"id":"library","label":"圖書館"},{"id":"registrar","label":"註冊組"}],""" +
            """"tags":[],"default_tags":[]}"""
        val TAXONOMY_VALUE = TaxonomyResponse(
            orgs = listOf(OrgLabel("library", "圖書館"), OrgLabel("registrar", "註冊組")),
            tags = emptyList(),
            defaultTags = emptyList(),
        )
    }
}
