package org.ntust.app.tigerduck.data.moodle

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
class MoodleCourseRepository internal constructor(
    private val signedIn: () -> Boolean,
    /** The account the stores are for; null with none. */
    private val account: () -> String?,
    private val isDemo: () -> Boolean,
    private val liveApi: MoodleCourseApi,
    private val demoApi: () -> MoodleCourseApi?,
    private val idMap: suspend () -> Map<String, Int>,
    private val cachePort: (account: String) -> MoodleCachePort,
    private val picturesDir: (account: String) -> File?,
    private val openedFilesDir: (account: String) -> File?,
    private val decode: (ByteArray) -> ImageBitmap?,
    authState: Flow<Boolean>,
    appScope: CoroutineScope,
    private val newScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
) {
    @Inject
    constructor(
        live: LiveMoodleCourseApi,
        demoAccount: DemoAccount,
        dataCache: DataCache,
        authService: AuthService,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        signedIn = { authService.authState.value },
        // A demo session can present as signed in with no stored ID; its cache needs a folder all
        // the same.
        account = {
            authService.storedStudentId?.takeIf { it.isNotBlank() }
                ?: if (demoAccount.isActive) DEMO_ACCOUNT else null
        },
        isDemo = { demoAccount.isActive },
        liveApi = live,
        demoApi = demoAccount::moodleApi,
        idMap = dataCache::loadMoodleCourseIds,
        cachePort = { account -> DataCachePort(dataCache, account) },
        picturesDir = { account -> File(dataCache.moodleFilesDir(account), "pictures") },
        openedFilesDir = dataCache::moodleOpenedFilesDir,
        decode = ::decodeBitmap,
        authState = authService.authState,
        appScope = appScope,
    )

    private val lock = Mutex()
    private var stores = mutableMapOf<Pair<String, Int>, MoodleCourseStore>()
    private var scope = newScope()
    private var demo: MoodleCourseApi? = null

    /**
     * Bumped at every sign-out. A store holds the one it was built in, so one left on a page
     * further down the back stack is not current after signing back in, even as the same account:
     * its scope was cancelled with the sign-out.
     */
    @Volatile
    private var generation = 0

    init {
        appScope.launch {
            authState.collect { signedIn -> if (!signedIn) clear() }
        }
    }

    /**
     * The store for Moodle course [courseId], built on first use; null when signed out. [courseNo]
     * is the class table's course number, which the summary is also checked against.
     */
    suspend fun store(courseId: Int, courseNo: String? = null): MoodleCourseStore? = lock.withLock {
        if (!signedIn()) return@withLock null
        val account = account() ?: return@withLock null
        stores[account to courseId]?.let { return@withLock it }
        val api = if (isDemo()) {
            demo ?: demoApi()?.also { demo = it } ?: return@withLock null
        } else {
            liveApi
        }
        val ids = idMap()
        val built = generation
        val context = MoodleCacheContext(
            account = account,
            cache = cachePort(account),
            isCurrent = { generation == built && signedIn() && account() == account },
        )
        MoodleCourseStore(
            courseId = courseId,
            termCourseIds = MoodleCourseStore.termCourseIds(courseId, ids),
            courseCodes = MoodleCourseStore.courseCodes(courseId, courseNo, ids),
            api = api,
            context = context,
            scope = scope,
            picturesDir = picturesDir(account),
            openedFilesDir = openedFilesDir(account),
            decodePicture = decode,
        ).also { stores[account to courseId] = it }
    }

    /** Drops every store and stops what they were fetching. Their files go with the cache wipe. */
    private suspend fun clear() = lock.withLock {
        generation++
        scope.cancel()
        scope = newScope()
        stores = mutableMapOf()
        demo = null
    }

    private class DataCachePort(private val dataCache: DataCache, private val account: String) : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? =
            dataCache.loadMoodleData<T>(type, name, account)?.let { it.value to it.fetchedAtMs }

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) =
            dataCache.saveMoodleData(value, fetchedAtMs, name, account)
    }

    private companion object {
        const val DEMO_ACCOUNT = "demo"

        fun decodeBitmap(bytes: ByteArray): ImageBitmap? =
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }
}
