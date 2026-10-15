package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * While the import writes the person, nothing closes it: not Close, not Cancel, as iOS refuses a
 * swipe while busy. The page closes once the person is saved, and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableImportPageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val storeThread = HeldStoreDispatcher()
    private val store = ImportedClassTableStore({ ImportedClassTableStore.fileIn(app) }, { "B11300000" }, storeThread)
    private val model = ClassTableImportModel(
        store, { "B11300000" }, { true }, { _, _ -> listOf(Course(courseNo = "MA1010001", courseName = "Calculus")) }, scope,
    )
    private val code = ClassTableShareCode.encode(
        "B11300001", Instant.parse("2026-10-14T03:20:00Z"), listOf(ClassTableShareCode.Term("1151", listOf("MA1010001"))),
    )!!

    @After
    fun stop() = scope.cancel()

    @Test
    fun `close and cancel do nothing while the person is saved, and the page closes once it is`() {
        var closed = 0
        var finished = 0
        composeRule.setContent {
            ClassTableImportPage(model, onClose = { closed++ }, onFinished = { finished++ }, onPickPicture = {})
        }
        composeRule.runOnIdle { model.imageRead(listOf(code)) }
        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).assertIsEnabled()

        composeRule.onNodeWithText(app.getString(R.string.action_import)).performClick()
        composeRule.waitForIdle()

        // The lookup answered and the write is held: too late to leave.
        val close = composeRule.onNodeWithContentDescription(app.getString(R.string.action_close))
        close.assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(0, closed)
        assertEquals(0, finished)

        composeRule.runOnIdle { storeThread.release() }
        composeRule.waitForIdle()

        assertEquals(1, finished)
        assertEquals(0, closed)
        assertEquals(listOf("B11300001"), store.tables.value.people.map { it.nickname })
    }
}

/** Holds what is dispatched to it until [release] runs it, as a busy I/O thread would. */
private class HeldStoreDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        held.addLast(block)
    }

    fun release() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}
