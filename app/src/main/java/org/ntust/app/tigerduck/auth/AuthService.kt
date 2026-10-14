package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.shared.LibraryService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.util.logSummary

@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    private val pushRegistration: PushRegistrationService,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val authTokenManager: AuthTokenManager,
    private val moodleTokenService: MoodleTokenService,
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache,
    private val bulletinCache: BulletinCache,
    private val bulletinReadStateStore: BulletinReadStateStore,
    private val importedClassTables: ImportedClassTableStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val demoAccount: org.ntust.app.tigerduck.demo.DemoAccount,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
    private val mailAccount: org.ntust.app.tigerduck.mail.MailAccount,
) {
    /**
     * A demo session presents as signed in, with or without an account.
     *
     * `authState` is what every screen asks, so a device that skipped the
     * wizard replaced its whole content with "not signed in" and no fixture
     * could put anything on it -- the fake timetable was on disk, and nothing
     * would draw it. Reporting true here is what lets a clean install be
     * photographed at all.
     *
     * Safe only because demo mode also refuses every request
     * ([org.ntust.app.tigerduck.debug.DemoModeInterceptor]): nothing tries to
     * use the credentials that are not there, and the screens take the
     * no-network path they already had. Nothing lowers the flag behind our
     * back either -- `logout()` is reached from the settings entry and the
     * full reset, both of which are the user saying so.
     *
     * Sampled once, at process start, like the rest of demo mode.
     */
    private val demoMode = demoAccount.isActive

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    /**
     * Observable "is a user signed in" state, backed by the stored NTUST
     * credentials — so it is **durable**: it survives process death, reboots
     * and upgrades, and only flips on an actual login or logout.
     *
     * This is the signal almost everything wants, including every feature
     * that only reads the local cache. Session *liveness* — whether an SSO
     * cookie is currently warm enough to make a network call — is a separate,
     * much shorter-lived question; ask [NtustSessionManager.cookiesValid] for
     * it, or better, call [ensureAuthenticated], which re-logs in from stored
     * credentials rather than merely reporting that it cannot.
     *
     * Conflating the two is a live hazard: the cookie jar is in-memory, so a
     * liveness check reads false after every process restart. Gating the Live
     * Update on one made it vanish after each reboot or background kill until
     * the user signed in by hand.
     */
    private val _authState = MutableStateFlow(demoMode || credentials.ntustStudentId != null)
    val authState: StateFlow<Boolean> = _authState

    private val loginMutex = Mutex()

    // The automatic backend sign-in, after a refused refresh and whenever
    // something finds no session.
    private val storedAccountSignIn = StoredAccountSignIn(
        tokens = authTokenManager,
        isDemo = { demoAccount.isActive },
        studentId = { credentials.ntustStudentId },
        password = { credentials.ntustPassword },
        storedMoodleToken = { credentials.moodleToken },
        // Unlike obtainToken(), stores no token once the account has signed
        // out, so a sign-out during the harvest leaves none for the next one.
        harvestMoodleToken = { moodleTokenService.refreshToken() },
        afterSignIn = {
            appScope.launch {
                runCatching { pushRegistration.onSignedIn() }
                    .onFailure { e -> android.util.Log.w("AuthService", "registration after auto sign-in failed: ${e.logSummary()}") }
            }
            // Deliver a live_activity edit that is still unconfirmed: one made
            // while signed out, or while the v3 JWT had lapsed. With nothing
            // pending this sends nothing, so this device's values never
            // overwrite what another device wrote. It must not mark anything
            // either, because this sign-in can run from inside a pull's own
            // request — see NotificationSettingsSync.pushIfUnconfirmed.
            notificationSettingsSync.pushIfUnconfirmed()
        },
    )

    // Run under loginMutex, by login() and ensureAuthenticated().
    private val schoolSignIn = SchoolSignIn(
        libraryStore = credentials,
        libraryService = libraryService,
        studentId = { credentials.ntustStudentId },
        password = { credentials.ntustPassword },
        sessionIsWarm = { sessionManager.cookiesValid },
        signInToSchool = { studentId, password, typed ->
            val serviceUrl = "https://courseselection.ntust.edu.tw/"
            if (typed) {
                ssoLoginService.signIn(serviceUrl, studentId, password)
            } else {
                ssoLoginService.ensureServiceLogin(serviceUrl, studentId, password)
            }
        },
        // A library-only demo sign-in may still be standing in; see
        // [org.ntust.app.tigerduck.demo.DemoAccount.signOutLibrary].
        afterLibrarySignIn = { demoAccount.signOutLibrary() },
    )

    init {
        authTokenManager.signInWithStoredAccount = storedAccountSignIn::signIn
    }

    /**
     * Signs in to the backend again after it refused the session, as the
     * automatic sign-in: it shares an attempt that is under way, and makes
     * none in the pause after one that failed. Says whether it signed in.
     */
    suspend fun attemptRelogin(): Boolean = authTokenManager.signInAgain()

    val storedStudentId: String? get() = credentials.ntustStudentId

    /**
     * Whether the account [studentId] named, read before some suspending
     * work, is still the one signed in: false after a sign-out, or a sign-in
     * as someone else, in the meantime. What that work read from the cache
     * then belongs to the account that left.
     */
    fun isStillSignedInAs(studentId: String?): Boolean =
        authState.value && storedStudentId == studentId
    internal val storedPassword: String? get() = credentials.ntustPassword
    val storedMoodleToken: String? get() = credentials.moodleToken

    suspend fun login(studentId: String, password: String): Boolean = loginMutex.withLock {
        _isLoggingIn.value = true
        _loginError.value = null

        try {
            val normalizedId = studentId.trim().uppercase()

            // The store-review account signs in without a server — see
            // DemoAccount. Checked before anything goes out, so these
            // credentials never reach NTUST.
            if (demoAccount.matches(normalizedId, password)) {
                demoAccount.enter()
                credentials.ntustStudentId = normalizedId
                credentials.ntustPassword = password
                importedClassTables.accountDidSignIn(normalizedId)
                _authState.value = true
                _isLoggingIn.value = false
                return@withLock true
            }

            val success = schoolSignIn.typed(normalizedId, password)

            if (success) {
                // Registered before authState flips: an automatic sign-in it
                // sets off, such as Home's pull, waits for this one instead of
                // logging in too. signIn is read before the credentials are
                // stored, so a sign-out from here on keeps the v3 login below
                // from storing a pair.
                authTokenManager.signInInteractively { signIn ->
                    credentials.ntustStudentId = normalizedId
                    credentials.ntustPassword = password
                    // Before the class table can show: another account must not inherit what the last
                    // one imported, whether imported while signed out or left by a wipe that never ran.
                    importedClassTables.accountDidSignIn(normalizedId)
                    _authState.value = true
                    // Best-effort: v3 JWT login failures must not block the SSO
                    // session. The automatic sign-in tries again later; this one
                    // never waits for its pause.
                    // Harvest a FRESH Moodle token before the v3 login. The backend
                    // verifies it against moodle2, and the stored token is stale or
                    // empty right after SSO (especially on a fresh install) — which
                    // makes the backend reject the login with 401 invalid_token.
                    // iOS does the same obtain-then-login. Best-effort: fall back to
                    // any stored token, and never let a harvest failure block the
                    // SSO session.
                    val moodleToken = try {
                        moodleTokenService.obtainToken(normalizedId, password)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("AuthService", "Moodle token harvest failed; using stored: ${e.logSummary()}")
                        credentials.moodleToken
                    }
                    if (moodleToken.isNullOrEmpty()) {
                        // A backend login without one could only be refused, and
                        // would count against the login limit. The automatic
                        // sign-in tries again.
                        android.util.Log.w("AuthService", "v3 login skipped: no Moodle token")
                        return@signInInteractively SignInAttempt.FAILED
                    }
                    try {
                        authTokenManager.login(
                            studentId = normalizedId,
                            moodleToken = moodleToken,
                            moodlePrivateToken = null,
                            signInGeneration = signIn,
                        )
                        SignInAttempt.SIGNED_IN
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("AuthService", "v3 login failed (best-effort): ${e.logSummary()}")
                        SignInAttempt.FAILED
                    }
                }
                runCatching { pushRegistration.onSignedIn() }
                    .onFailure { e -> if (e is CancellationException) throw e }
                // Same catch-up as the automatic sign-in, for a fresh SSO login.
                notificationSettingsSync.pushIfUnconfirmed()
            }

            _isLoggingIn.value = false
            success
        } catch (e: CancellationException) {
            _isLoggingIn.value = false
            throw e
        } catch (e: Exception) {
            _loginError.value = if (e is SsoLoginError.NetworkError) {
                context.getString(R.string.error_network_unavailable)
            } else {
                e.message ?: context.getString(R.string.error_sign_in_failed)
            }
            _isLoggingIn.value = false
            false
        }
    }

    suspend fun ensureAuthenticated(): Boolean = loginMutex.withLock {
        // No session to build: every caller then reads its cache, which is
        // where the demo data is.
        if (demoAccount.isActive) return@withLock false
        try {
            schoolSignIn.stored()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun logout() {
        // Snapshot the bearer BEFORE wiping tokens: clearNtustCredentials() and
        // authTokenManager.logout() both null the v3 access token, and
        // pushRegistration.unregister() runs its DELETE fire-and-forget on its
        // own scope — so without a captured header the device-unregister call
        // would find no session to go out with, and leak the device row.
        val authHeader = authTokenManager.currentAuthHeader()
        // A school mail account signed in as the same student goes with it, and its background
        // checks and notifications stop; another student's stays.
        credentials.ntustStudentId?.let { mailAccount.signOutIfSchoolAccount(it) }
        credentials.clearNtustCredentials()
        credentials.clearLibraryCredentials()
        authTokenManager.logout()
        // A queued live_activity push, and an edit still waiting to be
        // confirmed, both belong to the account that is leaving. Neither may
        // reach whoever signs in next.
        notificationSettingsSync.cancelPendingPushes()
        // Before the session goes: a 選課清單 fetch still running would
        // otherwise log the departing account back in, and the next one to
        // sign in would find its session warm and read its roster.
        courseService.cancelSharedFetches()
        moodleService.cancelSharedFetches()
        sessionManager.signOut()
        bulletinReadStateStore.clear()
        // The cache they dated is wiped below; left behind, they would date
        // the next account's data, and hold its first background sync off.
        prefs.clearSyncStamps()
        // A re-enable check compares this device's data, which is wiped below.
        // Left pending, it would hold the next session's assignment marks and
        // then offer Use Local, sending that emptied data over the server's.
        prefs.pendingConflictCategories = emptySet()
        _loginError.value = null
        _authState.value = false
        pushRegistration.unregister(authHeader)
        // Last, so everything above still sees the demo account and keeps
        // its requests on the device.
        demoAccount.exit()
        // Wipe persisted user data on the application scope so a coroutine
        // launched from a transient ViewModel scope can't be cancelled mid-
        // delete when the user backs out of Settings or the activity dies.
        // Without this guarantee, the JSON cache survives logout and bleeds
        // into the next account on the same device.
        appScope.launch {
            // Only this sign-out empties them: a relogin in the background or a renewed session
            // keeps the class tables the user imported.
            runCatching { importedClassTables.clearForSignOut() }
                .onFailure { android.util.Log.w("AuthService", "clearForSignOut failed on logout: ${it.logSummary()}") }
            runCatching { dataCache.clearAllUserData() }
                .onFailure { android.util.Log.w("AuthService", "clearAllUserData failed on logout: ${it.logSummary()}") }
            // Bulletins are school-wide rather than private, but the read-state
            // store is already cleared above — clear the content snapshot too
            // so the next session starts coherent instead of half-stale.
            runCatching { bulletinCache.clear() }
                .onFailure { android.util.Log.w("AuthService", "bulletinCache.clear failed on logout: ${it.logSummary()}") }
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }
}
