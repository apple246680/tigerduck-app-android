package org.ntust.app.tigerduck.data.moodle

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.moodle.LiveMoodleCourseApi
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [MoodleCourseStore] of each course the signed-in account opens, shared by every Moodle
 * screen of that course for as long as the app runs, so a screen pushed from another finds what
 * that one fetched. A sign-out drops them all; the next account builds its own, over its own
 * cache folder. The demo account's stores answer from the bundled file.
 */
@Singleton
class MoodleCourseRepository @Inject constructor(
    private val live: LiveMoodleCourseApi,
    private val demoAccount: DemoAccount,
    private val dataCache: DataCache,
    private val authService: AuthService,
    @param:ApplicationScope appScope: CoroutineScope,
) {
    private val lock = Mutex()
    private var stores = mutableMapOf<Pair<String, Int>, MoodleCourseStore>()
    private var scope = newScope()
    private var demoApi: MoodleCourseApi? = null

    init {
        appScope.launch {
            authService.authState.collect { signedIn -> if (!signedIn) clear() }
        }
    }

    /** The account the stores are for: the stored student ID, or the demo account's stand-in. */
    private fun currentAccount(): String? =
        authService.storedStudentId?.takeIf { it.isNotBlank() }
            ?: if (demoAccount.isActive) DEMO_ACCOUNT else null

    /**
     * The store for Moodle course [courseId], built on first use; null when signed out. [courseNo]
     * is the class table's course number, which the summary is also checked against.
     */
    suspend fun store(courseId: Int, courseNo: String? = null): MoodleCourseStore? = lock.withLock {
        if (!authService.authState.value) return@withLock null
        val account = currentAccount() ?: return@withLock null
        stores[account to courseId]?.let { return@withLock it }
        val idMap = dataCache.loadMoodleCourseIds()
        val api = if (demoAccount.isActive) {
            demoApi ?: demoAccount.moodleApi()?.also { demoApi = it } ?: return@withLock null
        } else {
            live
        }
        val context = MoodleCacheContext(
            account = account,
            cache = DataCachePort(dataCache, account),
            isCurrent = { authService.authState.value && currentAccount() == account },
        )
        MoodleCourseStore(
            courseId = courseId,
            termCourseIds = MoodleCourseStore.termCourseIds(courseId, idMap),
            courseCodes = MoodleCourseStore.courseCodes(courseId, courseNo, idMap),
            api = api,
            context = context,
            scope = scope,
            picturesDir = File(dataCache.moodleFilesDir(account), "pictures"),
            openedFilesDir = dataCache.moodleOpenedFilesDir(account),
            decodePicture = ::decode,
        ).also { stores[account to courseId] = it }
    }

    /** Drops every store and stops what they were fetching. Their files go with the cache wipe. */
    private suspend fun clear() = lock.withLock {
        scope.cancel()
        scope = newScope()
        stores = mutableMapOf()
        demoApi = null
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    private class DataCachePort(private val dataCache: DataCache, private val account: String) : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? =
            dataCache.loadMoodleData<T>(type, name, account)?.let { it.value to it.fetchedAtMs }

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) =
            dataCache.saveMoodleData(value, fetchedAtMs, name, account)
    }

    private companion object {
        /** A demo session can present as signed in with no stored ID; its cache needs a folder all the same. */
        const val DEMO_ACCOUNT = "demo"
    }
}
