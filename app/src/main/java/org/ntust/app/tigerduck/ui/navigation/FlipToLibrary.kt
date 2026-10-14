package org.ntust.app.tigerduck.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
 *   - [FlipToLibraryGate.isArmed]: the user has turned it on via
 *     `appState.flipToLibraryEnabled` and a library account is signed in, as
 *     the switch in Settings shows it, AND
 *   - the host activity is at least STARTED.
 *
 * A flip checks the sign-in again as it fires: the token can run out between
 * the last [org.ntust.app.tigerduck.library.LibrarySession.refresh] and the flip.
 *
 * Net effect: when the user flips the phone face-down with a valid library
 * session, the nav controller jumps to [Screen.Library]; a flip while already
 * on Library is a no-op.
 */
@Composable
fun FlipToLibraryEffect(
    navController: NavController,
    appState: AppState,
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

    // Reading the Compose-state-backed switch and the sign-in here makes the
    // enclosing composition observe changes, so the DisposableEffect below
    // restarts when either changes.
    val signedIn by appState.librarySession.signedIn.collectAsStateWithLifecycle()
    val armed = FlipToLibraryGate.isArmed(
        enabled = appState.flipToLibraryEnabled,
        signedInToLibrary = signedIn,
    )

    // Asks once whether to turn the gesture on: right after a library sign-in,
    // or at a launch already signed in. The root host holds it behind What's
    // New and the update prompt; the wizard, which signs in too, is over
    // before this is composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(signedIn, appState.flipToLibraryEnabled, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (FlipToLibraryGate.offersGesture(
                    signedInToLibrary = signedIn,
                    enabled = appState.flipToLibraryEnabled,
                    asked = firstTriggerController.hasSeen(FirstTriggerPromptKey.FLIP_TO_LIBRARY),
                )
            ) {
                FlipToLibraryPrompt.request(firstTriggerController, appState)
            }
        }
    }

    // Cold-start race: MainNavigation installs this effect before NavHost
    // declares its graph, so a flip during initial composition lands before
    // navigate() has any destinations to route to. Dropping the callback
    // would also strand the detector in FaceDown (it only fires once per
    // Upright -> FaceDown transition), forcing the user to re-arm with a
    // full upright -> face-down cycle before the next try would register.
    // Queue the pending flip and consume it once the back stack appears.
    var pendingFlip by remember(navController, appState) { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(navController, appState) {
        FlipDetector(context) {
            // Fire-time guards. All checks are synchronous and main-thread —
            // sensor callbacks deliver on the main thread by default. Re-checking
            // the switch closes the brief window where the user toggles
            // flipToLibrary off and a sensor event lands before
            // DisposableEffect restarts and unregisters the detector. Only
            // someone who turned it on is listening, so a flip that passes
            // these opens Library straight away.
            if (!appState.flipToLibraryEnabled) return@FlipDetector
            if (!appState.librarySession.refresh()) return@FlipDetector
            if (navController.currentBackStackEntry == null) {
                pendingFlip = true
                return@FlipDetector
            }
            navigateToLibrary(navController, context)
        }
    }

    if (pendingFlip) {
        LaunchedEffect(Unit) {
            snapshotFlow { navController.currentBackStackEntry }
                .filterNotNull()
                .first()
            // Re-check gates at consume time — `enabled` and the session
            // can change while the back stack is being built.
            if (appState.flipToLibraryEnabled && appState.librarySession.refresh()) {
                navigateToLibrary(navController, context)
            }
            pendingFlip = false
        }
    }

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
 * Whether flip-to-library listens, and what its switch in Settings shows. The
 * flip opens the library's QR code, which needs a signed-in library account, so
 * until one is the switch reads off and takes no input; the choice it holds
 * comes back with the sign-in.
 */
internal object FlipToLibraryGate {
    fun isArmed(enabled: Boolean, signedInToLibrary: Boolean): Boolean = enabled && signedInToLibrary

    fun switchReadsOn(enabled: Boolean, signedInToLibrary: Boolean): Boolean = enabled && signedInToLibrary

    fun switchTakesInput(signedInToLibrary: Boolean): Boolean = signedInToLibrary

    /**
     * Whether to ask to turn the gesture on: signed in to the library, with the
     * switch off and the question never answered.
     */
    fun offersGesture(signedInToLibrary: Boolean, enabled: Boolean, asked: Boolean): Boolean =
        signedInToLibrary && !enabled && !asked
}

private fun navigateToLibrary(navController: NavController, context: Context) {
    val currentRoute = navController.currentDestination?.route
    if (currentRoute == Screen.Library.route) return
    navController.navigate(Screen.Library.route) {
        launchSingleTop = true
    }
    Haptics.perform(context, HapticScenario.FlipToLibrary)
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
    fun request(controller: FirstTriggerPromptController, appState: AppState) {
        controller.requestIfFirstTime(
            key = FirstTriggerPromptKey.FLIP_TO_LIBRARY,
            onAccept = { appState.flipToLibraryEnabled = true },
            onDecline = { appState.flipToLibraryEnabled = false },
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface FlipToLibraryEntryPoint {
    fun firstTriggerPromptController(): FirstTriggerPromptController
}
