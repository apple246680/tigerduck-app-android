package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics

/**
 * Side-effect composable that wires [FlipDetector] into the nav controller.
 *
 * The detector is registered only while:
 *   - the device has a rotation-vector sensor, AND
 *   - the user has opted in via `appState.flipToLibraryEnabled`, AND
 *   - the parent Library feature itself is on (`appState.libraryFeatureEnabled`),
 *     so disabling Library actually stops the sensor instead of leaving it
 *     running while every callback bails at a fire-time guard, AND
 *   - the host activity is at least STARTED.
 *
 * The library-session check ([AppState.isLibraryLoggedIn]) runs at fire time
 * inside the callback, not as a registration gate, because
 * [org.ntust.app.tigerduck.data.preferences.CredentialManager] only exposes
 * synchronous accessors — adding a flow purely for this gate is more plumbing
 * than the feature warrants.
 *
 * Net effect: when the user flips the phone face-down with a valid library
 * session, the nav controller jumps to [Screen.Library]; flips with no session,
 * already-on-Library or with something in front of the page (see
 * [FlipToLibraryGate]) are silently no-op.
 */
@Composable
fun FlipToLibraryEffect(
    navController: NavController,
    appState: AppState,
) {
    val context = LocalContext.current
    val firstTriggerController = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FlipToLibraryEntryPoint::class.java,
        ).firstTriggerPromptController()
    }
    FlipToLibraryEffect(
        navController = navController,
        flipToLibraryEnabled = { appState.flipToLibraryEnabled },
        libraryFeatureEnabled = { appState.libraryFeatureEnabled },
        isLibraryLoggedIn = { appState.isLibraryLoggedIn },
        hasSeenFirstTrigger = { firstTriggerController.hasSeen(FirstTriggerPromptKey.FLIP_TO_LIBRARY) },
        requestFirstTrigger = { FlipToLibraryFirstTrigger.request(firstTriggerController, appState) },
        onNavigated = { Haptics.perform(context, HapticScenario.FlipToLibrary) },
    )
}

/**
 * [FlipToLibraryEffect] with its app-wide state read through lambdas, so a
 * test can drive a real detector, window and back stack without Hilt.
 * [onNavigated] runs after a flip has opened Library (the haptic).
 *
 * [flipToLibraryEnabled] and [libraryFeatureEnabled] are also read during
 * composition: they must read snapshot state, so a change re-registers the
 * detector.
 */
@Composable
internal fun FlipToLibraryEffect(
    navController: NavController,
    flipToLibraryEnabled: () -> Boolean,
    libraryFeatureEnabled: () -> Boolean,
    isLibraryLoggedIn: () -> Boolean,
    hasSeenFirstTrigger: () -> Boolean,
    requestFirstTrigger: () -> Unit,
    onNavigated: () -> Unit,
) {
    val context = LocalContext.current
    val supported = remember(context) { FlipDetector.isSupported(context) }
    if (!supported) return

    // Reading the Compose-state-backed properties here makes the enclosing
    // composition observe changes, so the DisposableEffect below restarts
    // when the user toggles either setting.
    val enabled = flipToLibraryEnabled() && libraryFeatureEnabled()

    val currentFlipEnabled by rememberUpdatedState(flipToLibraryEnabled)
    val currentLibraryEnabled by rememberUpdatedState(libraryFeatureEnabled)
    val currentLoggedIn by rememberUpdatedState(isLibraryLoggedIn)
    val currentHasSeenFirstTrigger by rememberUpdatedState(hasSeenFirstTrigger)
    val currentRequestFirstTrigger by rememberUpdatedState(requestFirstTrigger)
    val currentOnNavigated by rememberUpdatedState(onNavigated)
    // The app window's; a dialog or sheet is a window of its own.
    val windowInfo by rememberUpdatedState(LocalWindowInfo.current)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController) {
        FlipDetector(context) {
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default. Re-checking
            // both gates (not just libraryFeatureEnabled) closes the brief window
            // where the user toggles flipToLibrary off and a sensor event lands
            // before DisposableEffect restarts and unregisters the detector.
            if (!currentLibraryEnabled()) return@FlipDetector
            if (!currentFlipEnabled()) return@FlipDetector
            // Gate the prompt behind the library session. The first-trigger
            // prompt explains the gesture in lieu of navigating, so it must
            // only be consumed on a flip that could actually navigate —
            // otherwise a signed-out user who flips by accident taps Keep,
            // burns the one-time prompt, and their first *working* flip after
            // signing in jumps straight to Library with no explanation.
            if (!currentLoggedIn()) return@FlipDetector
            // Not over a dialog, a sheet or a mail draft: leaving the page
            // drops what the user was in the middle of, and the first-trigger
            // prompt would land on top of it. Nothing is consumed, so the
            // next flip with nothing open works.
            if (!FlipToLibraryGate.mayNavigate(
                    windowFocused = windowInfo.isWindowFocused,
                    currentRoute = navController.currentDestination?.route,
                )
            ) return@FlipDetector
            // First-trigger UX: the first successful flip surfaces a root-level
            // keep/turn-off prompt instead of jumping tabs, so the user isn't
            // dropped into an unfamiliar screen before agreeing to the gesture.
            // The root dialog needs no back stack, so this runs before the
            // back-stack guard below.
            if (!currentHasSeenFirstTrigger()) {
                currentRequestFirstTrigger()
                return@FlipDetector
            }
            // No graph yet: dropped like any other ignored flip. This used to
            // queue the flip for the graph to appear, but no flip can get here
            // before it does. A flip fires at the earliest 800 ms of sensor
            // events (two 400 ms debounce windows) after the detector registers,
            // and the detector registers in the same frame that composes
            // MainNavigation's NavHost, which sets the graph; the window
            // focus the gate above requires also arrives only after that
            // first frame.
            if (navController.currentBackStackEntry == null) return@FlipDetector
            if (navigateToLibrary(navController)) currentOnNavigated()
        }
    }

    DisposableEffect(enabled, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (enabled) detector.register()
                Lifecycle.Event.ON_STOP -> detector.unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            detector.register()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            detector.unregister()
        }
    }
}

/** Whether it navigated: a flip on Library itself stays put, without the haptic. */
private fun navigateToLibrary(navController: NavController): Boolean {
    val currentRoute = navController.currentDestination?.route
    if (currentRoute == Screen.Library.route) return false
    navController.navigate(Screen.Library.route) {
        launchSingleTop = true
    }
    return true
}

/**
 * Shared builder for the flip-to-library first-trigger prompt. Extracted so the
 * debug Triggers screen can replay the exact prompt a real face-down gesture
 * surfaces. No-ops once the prompt has been seen — call
 * [FirstTriggerPromptController.reset] first to re-test it.
 *
 * Keep leaves the toggle on and does not navigate on this first flip (the user
 * has only just learned the gesture). Turn off disables the feature, which the
 * `DisposableEffect` in [FlipToLibraryEffect] observes to tear down the sensor.
 */
object FlipToLibraryFirstTrigger {
    fun request(controller: FirstTriggerPromptController, appState: AppState) {
        controller.requestIfFirstTime(
            key = FirstTriggerPromptKey.FLIP_TO_LIBRARY,
            onAccept = { /* keep enabled; no navigation on the first flip */ },
            onDecline = { appState.flipToLibraryEnabled = false },
        )
    }
}

/**
 * Whether a face-down flip may leave the current page for Library. The
 * iPhone ignores the flip while a sheet or alert is presented, since the
 * switch would drop whatever the user was in the middle of.
 *
 * Android has no "something is presented" flag, so this asks whether the
 * app's window has the input focus, which every window in front of it takes.
 * That makes the flip do nothing, by design, whenever the app is visible but
 * something else holds the focus:
 *   - in split screen, a freeform or pop-up window, or DeX, while the other
 *     app is the one last touched;
 *   - while a system dialog is up: a permission prompt, the low-battery or
 *     USB dialog, the share sheet;
 *   - while the notification shade, an expanded bubble or an edge panel is
 *     open.
 * A heads-up notification, the volume panel, a toast and the keyboard take
 * no focus, so a flip with one of them showing still works. Once the focus
 * is back, the next flip (upright, then face down again) works.
 */
internal object FlipToLibraryGate {
    /**
     * Pages the iPhone presents as a sheet, which the flip leaves alone
     * there: the mail composer, whose draft would be dropped.
     */
    private val SHEET_ROUTES = setOf(Screen.SchoolMailCompose.route)

    /**
     * [windowFocused] is whether the app's own window has the focus. A
     * dialog, a bottom sheet, a dropdown menu, a system permission prompt
     * and the pulled-down notification shade are each a window of their
     * own that takes it, so this one check covers them all, the way the
     * iPhone asks UIKit whether anything is presented.
     */
    fun mayNavigate(windowFocused: Boolean, currentRoute: String?): Boolean =
        windowFocused && currentRoute !in SHEET_ROUTES
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface FlipToLibraryEntryPoint {
    fun firstTriggerPromptController(): FirstTriggerPromptController
}
