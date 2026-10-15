package org.ntust.app.tigerduck.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.library.FlipToLibrarySetting
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.sensor.FlipSensor
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
 *   - [FlipToLibraryGate.isArmed]: the user has turned it on via
 *     `appState.flipToLibraryEnabled` and a library account is signed in, as
 *     the switch in Settings shows it, AND
 *   - the host activity is at least STARTED.
 *
 * A flip checks the sign-in again as it fires: the account can be refused or
 * signed out between the last
 * [org.ntust.app.tigerduck.library.LibrarySession.refresh] and the flip. An
 * expired token with stored credentials still counts, since the Library page
 * signs in again behind the code (tigerduck-app/tigerduck-app#528).
 *
 * Net effect: when the user flips the phone face-down with a valid library
 * session, the nav controller jumps to [Screen.Library]; flips already on
 * Library or with something in front of the page (see [FlipToLibraryGate])
 * are silently no-op.
 */
@Composable
fun FlipToLibraryEffect(
    navController: NavController,
    appState: AppState,
    /** Whether a page the app was opened on, such as the library shortcut's, is still to open. */
    startRoutePending: Boolean = false,
) {
    val context = LocalContext.current
    val supported = remember(context) { FlipDetector.isSupported(context) }
    if (!supported) return

    val firstTriggerController = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FlipToLibraryEntryPoint::class.java,
        ).firstTriggerPromptController()
    }

    val signedIn by appState.librarySession.signedIn.collectAsStateWithLifecycle()

    // Asks once whether to turn the gesture on: right after a library sign-in,
    // or at a launch already signed in. Not over the Library page, where it
    // would cover the code someone just signed in to show: leaving the page
    // asks (tigerduck-app/tigerduck-app#529). Nor before the first page is
    // up, which can be Library, nor while the page the app was opened on is
    // still to open. The root host holds it behind What's New and
    // the update prompt, and while a Library page shows; the wizard, which
    // signs in too, is over before this is composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEntry by navController.currentBackStackEntryAsState()
    val onLibraryPage = FlipToLibraryGate.offerWaitsForPage(currentEntry?.destination?.route, startRoutePending)
    LaunchedEffect(signedIn, appState.flipToLibraryEnabled, onLibraryPage, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (FlipToLibraryGate.offersGesture(
                    signedInToLibrary = signedIn,
                    enabled = appState.flipToLibraryEnabled,
                    asked = firstTriggerController.hasSeen(FirstTriggerPromptKey.FLIP_TO_LIBRARY),
                    onLibraryPage = onLibraryPage,
                )
            ) {
                FlipToLibraryPrompt.request(firstTriggerController, appState.flipToLibrary)
            }
        }
    }

    FlipToLibraryEffect(
        navController = navController,
        flipToLibraryEnabled = { appState.flipToLibraryEnabled },
        signedInToLibrary = { signedIn },
        stillSignedInToLibrary = { appState.librarySession.refresh() },
        onNavigated = { Haptics.perform(context, HapticScenario.FlipToLibrary) },
    )
}

/**
 * [FlipToLibraryEffect] with its app-wide state read through lambdas, so a
 * test can drive a real detector, window and back stack without Hilt.
 * [stillSignedInToLibrary] checks the sign-in again as a flip fires.
 * [onNavigated] runs after a flip has opened Library (the haptic).
 * [newSensor] builds the detector, or returns null where the phone has no
 * sensor for it; an instrumented test passes one it can fire by hand.
 *
 * [flipToLibraryEnabled] and [signedInToLibrary] are also read during
 * composition: they must read snapshot state, so a change re-registers the
 * detector.
 */
@Composable
internal fun FlipToLibraryEffect(
    navController: NavController,
    flipToLibraryEnabled: () -> Boolean,
    signedInToLibrary: () -> Boolean,
    stillSignedInToLibrary: () -> Boolean,
    onNavigated: () -> Unit,
    newSensor: (Context, onFaceDown: () -> Unit) -> FlipSensor? = ::flipDetectorIfSupported,
) {
    val context = LocalContext.current

    val currentFlipEnabled by rememberUpdatedState(flipToLibraryEnabled)
    val currentStillSignedIn by rememberUpdatedState(stillSignedInToLibrary)
    val currentOnNavigated by rememberUpdatedState(onNavigated)
    // The app window's; a dialog or sheet is a window of its own.
    val windowInfo by rememberUpdatedState(LocalWindowInfo.current)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController) {
        newSensor(context) onFaceDown@{
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default. Re-checking
            // the switch closes the brief window where the user toggles
            // flipToLibrary off and a sensor event lands before
            // DisposableEffect restarts and unregisters the detector. Only
            // someone who turned it on is listening, so a flip that passes
            // these opens Library straight away.
            if (!currentFlipEnabled()) return@onFaceDown
            if (!currentStillSignedIn()) return@onFaceDown
            // Not over a dialog, a sheet or a mail draft: leaving the page
            // drops what the user was in the middle of. Nothing is consumed,
            // so the next flip with nothing open works.
            if (!FlipToLibraryGate.mayNavigate(
                    windowFocused = windowInfo.isWindowFocused,
                    currentRoute = navController.currentDestination?.route,
                )
            ) return@onFaceDown
            // No graph yet: dropped like any other ignored flip. This used to
            // queue the flip for the graph to appear, but no flip can get here
            // before it does. A flip fires at the earliest 800 ms of sensor
            // events (two 400 ms debounce windows) after the detector registers,
            // and the detector registers in the same frame that composes
            // MainNavigation's NavHost, which sets the graph; the window
            // focus the gate above requires also arrives only after that
            // first frame.
            if (navController.currentBackStackEntry == null) return@onFaceDown
            if (navigateToLibrary(navController)) currentOnNavigated()
        }
    } ?: return // No rotation-vector sensor on this phone.

    // Reading the Compose-state-backed switch and the sign-in here makes the
    // enclosing composition observe changes, so the DisposableEffect below
    // restarts when either changes.
    val armed = FlipToLibraryGate.isArmed(
        enabled = flipToLibraryEnabled(),
        signedInToLibrary = signedInToLibrary(),
    )

    DisposableEffect(armed, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (armed) detector.register()
                Lifecycle.Event.ON_STOP -> detector.unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (armed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            detector.register()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            detector.unregister()
        }
    }
}

/**
 * Whether flip-to-library listens, what its switch in Settings shows, when it
 * offers itself, and whether a flip may leave the current page.
 *
 * The flip opens the library's QR code, which needs a signed-in library
 * account, so until one is the switch reads off and takes no input; the
 * choice it holds comes back with the sign-in.
 *
 * The iPhone ignores the flip while a sheet or alert is presented, since the
 * switch would drop whatever the user was in the middle of. Android has no
 * "something is presented" flag, so [mayNavigate] asks whether the app's
 * window has the input focus, which every window in front of it takes. That
 * makes the flip do nothing, by design, whenever the app is visible but
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

    fun isArmed(enabled: Boolean, signedInToLibrary: Boolean): Boolean = enabled && signedInToLibrary

    fun switchReadsOn(enabled: Boolean, signedInToLibrary: Boolean): Boolean = enabled && signedInToLibrary

    fun switchTakesInput(signedInToLibrary: Boolean): Boolean = signedInToLibrary

    /**
     * Whether to ask to turn the gesture on: signed in to the library, with the
     * switch off and the question never answered. Not over the Library page,
     * where the prompt would cover the code someone just signed in to show;
     * leaving the page asks.
     */
    fun offersGesture(signedInToLibrary: Boolean, enabled: Boolean, asked: Boolean, onLibraryPage: Boolean): Boolean =
        signedInToLibrary && !enabled && !asked && !onLibraryPage

    /**
     * Whether the offer waits for the page shown, [currentRoute]: on the
     * Library page, and before the back stack has a page at all, as in the
     * first composition of a cold start, whose first page can be Library (the
     * first tab). Also while a page the app was opened on is still to open
     * ([startRoutePending]): the library shortcut opens the first tab for a
     * frame before Library.
     */
    fun offerWaitsForPage(currentRoute: String?, startRoutePending: Boolean): Boolean =
        startRoutePending || currentRoute == null || currentRoute == Screen.Library.route

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

/** A [FlipDetector] where the phone has the sensor it reads, else nothing to register. */
private fun flipDetectorIfSupported(context: Context, onFaceDown: () -> Unit): FlipSensor? =
    if (FlipDetector.isSupported(context)) FlipDetector(context, onFaceDown) else null

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
 * The question that offers the flip gesture, shared with the debug Triggers
 * screen, which replays it after [FirstTriggerPromptController.reset]. No-ops
 * once the question has been answered.
 *
 * Turn on stores the switch on, which the `DisposableEffect` in
 * [FlipToLibraryEffect] observes to start the sensor. Maybe later stores it
 * off: an answer, which the default off is not.
 */
object FlipToLibraryPrompt {
    fun request(controller: FirstTriggerPromptController, setting: FlipToLibrarySetting) {
        controller.requestIfFirstTime(
            key = FirstTriggerPromptKey.FLIP_TO_LIBRARY,
            onAccept = { setting.enabled = true },
            onDecline = { setting.enabled = false },
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface FlipToLibraryEntryPoint {
    fun firstTriggerPromptController(): FirstTriggerPromptController
}
