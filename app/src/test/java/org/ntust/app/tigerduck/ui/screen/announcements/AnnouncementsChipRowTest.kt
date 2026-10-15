package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.OrgLabel
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A department already selected when the chips first appear is scrolled into view. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsChipRowTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply {
        announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        rememberAnnouncementFilter = true
    }
    private val cache = BulletinCache(context)

    /** More departments than the row has room for; the backend itself never answers. */
    private val repository = BulletinRepository().apply {
        runBlocking {
            getOrFetchTaxonomy {
                TaxonomyResponse(
                    orgs = (0 until 12).map { OrgLabel("org$it", "處室$it") },
                    tags = emptyList(),
                    defaultTags = emptyList(),
                )
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun open() {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        val vm = AnnouncementsViewModel(
            api, cache, repository, BulletinReadStateStore(context), prefs, testDemoAccount(context, prefs, cache),
        )
        composeRule.setContent {
            MaterialTheme { AnnouncementsScreen(onOpenBulletin = {}, onOpenSubscriptions = {}, viewModel = vm) }
        }
    }

    @Test
    fun `a remembered department far along the row is in view when the page opens`() {
        prefs.savedAnnouncementOrgs = setOf("org11")
        open()
        composeRule.onNodeWithText("處室11").assertIsDisplayed()
    }

    @Test
    fun `with nothing selected the row opens at its start`() {
        open()
        composeRule.onNodeWithText("處室0").assertIsDisplayed()
    }
}
