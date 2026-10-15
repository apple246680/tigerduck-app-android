// Drives the real flip effect: a rotation-vector sensor fed face-up then face-down
// readings, a real NavHost, and the activity window's focus switched off and on the way
// a dialog or the shade switches it. FlipToLibraryGateTest pins the rule; this pins that
// the effect asks it, so a flip over a dialog cannot quietly reach Library again.

package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.ComposeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class FlipToLibraryEffectTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var sensor: Sensor
    private var nowNanos = 0L
    private var haptics = 0

    @Before
    fun setUp() {
        sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_ROTATION_VECTOR).build()
        shadowOf(sensorManager()).addSensor(sensor)
    }

    private fun sensorManager() = composeRule.activity.getSystemService(SensorManager::class.java)

    /** The app as MainNavigation wires it: the effect first, then the NavHost it drives. */
    private fun show(withGraph: Boolean = true) {
        composeRule.setContent {
            navController = rememberNavController()
            FlipToLibraryEffect(
                navController = navController,
                flipToLibraryEnabled = { true },
                signedInToLibrary = { true },
                stillSignedInToLibrary = { true },
                onNavigated = { haptics++ },
            )
            if (withGraph) {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {}
                    composable(Screen.Library.route) {}
                    composable(
                        Screen.SchoolMailCompose.route,
                        arguments = listOf(
                            navArgument("mode") { type = NavType.StringType; defaultValue = ComposeMode.NEW.name },
                            navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                            navArgument("uid") { type = NavType.LongType; defaultValue = -1L },
                        ),
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The window manager telling the activity's window it gained or lost the focus, through
     * the same ViewRootImpl call Robolectric's ActivityController makes, so every focus
     * listener Compose has hears it.
     */
    private fun focusWindow(focused: Boolean) {
        composeRule.runOnUiThread {
            val root = ReflectionHelpers.callInstanceMethod<Any>(composeRule.activity.window.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Any>(
                root,
                "windowFocusChanged",
                ClassParameter.from(Boolean::class.javaPrimitiveType, focused),
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** Face up long enough to arm, then face down long enough to fire: one flip. */
    private fun flip() {
        composeRule.runOnUiThread {
            listOf(UPRIGHT, UPRIGHT, FACE_DOWN, FACE_DOWN).forEach { values ->
                shadowOf(sensorManager()).sendSensorEventToListeners(
                    SensorEventBuilder.newBuilder(sensor, values.copyOf())
                        .setTimestamp(nowNanos)
                        .build(),
                )
                nowNanos += HALF_SECOND_NANOS
            }
        }
        composeRule.waitForIdle()
    }

    private fun route() = navController.currentDestination?.route

    @Test
    fun `the first flip after the app opens reaches Library`() {
        // The cold start a queued flip once covered: the effect is composed before the
        // NavHost, as in MainNavigation, and this is the earliest a flip can land. Sensor
        // events are only handled once the frame that composed both is done.
        show()
        focusWindow(true)
        flip()
        assertEquals(Screen.Library.route, route())
        assertEquals(1, haptics)
    }

    @Test
    fun `a flip while something else has the window's focus does nothing, and the next one works`() {
        show()
        // A dialog, a sheet, a menu or the shade: each takes the focus from the app's window.
        focusWindow(false)
        flip()
        assertEquals("home", route())
        assertEquals(0, haptics)

        focusWindow(true)
        flip()
        assertEquals(Screen.Library.route, route())
    }

    @Test
    fun `a flip on the mail composer does nothing`() {
        show()
        focusWindow(true)
        composeRule.runOnUiThread { navController.navigate(Screen.SchoolMailCompose.route(ComposeMode.NEW)) }
        composeRule.waitForIdle()
        flip()
        assertEquals(Screen.SchoolMailCompose.route, route())
    }

    @Test
    fun `a flip with no graph is dropped rather than crashing`() {
        show(withGraph = false)
        focusWindow(true)
        flip()
        assertNull(navController.currentBackStackEntry)
    }

    private companion object {
        val UPRIGHT = floatArrayOf(0f, 0f, 0f, 1f)
        // Half a turn about the x axis: the screen faces the floor.
        val FACE_DOWN = floatArrayOf(1f, 0f, 0f, 0f)
        const val HALF_SECOND_NANOS = 500_000_000L
    }
}
