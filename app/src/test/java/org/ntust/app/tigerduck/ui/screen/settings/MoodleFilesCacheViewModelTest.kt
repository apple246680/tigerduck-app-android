package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import java.io.File

/** Settings > Moodle materials: what the downloaded files take, and clearing them. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleFilesCacheViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun file(path: String, bytes: Int) = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test
    fun `the size is unknown until measured, then the files' total, and clearing deletes them all`() = runTest(UnconfinedTestDispatcher()) {
        val a = file("acct/5/m1/ab/1/a.pdf", 300)
        val b = file("acct/6/p9/cd/0/b.zip", 200)
        val viewModel = MoodleFilesCacheViewModel(
            MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined),
        )
        assertNull(viewModel.bytes.value)

        viewModel.measure()
        assertEquals(500L, viewModel.bytes.value)

        viewModel.clear()
        assertEquals(0L, viewModel.bytes.value)
        assertFalse(a.exists())
        assertFalse(b.exists())
    }
}
