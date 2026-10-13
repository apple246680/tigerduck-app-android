package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * R8 drops the android.util.Log calls that proguard-rules.pro names in its
 * -assumenosideeffects block. Verbose, debug and info lines print course
 * numbers, ids and server replies, so release builds drop them; warnings and
 * errors stay for bug reports.
 */
class ReleaseLogRulesTest {

    @Test
    fun `release builds drop verbose, debug and info logs and keep warnings and errors`() {
        val block = Regex("""-assumenosideeffects\s+class\s+android\.util\.Log\s*\{([^}]*)\}""")
            .find(File("proguard-rules.pro").readText())
            ?.groupValues?.get(1).orEmpty()
        assertEquals(
            listOf(
                "public static int v(...) return 0;",
                "public static int d(...) return 0;",
                "public static int i(...) return 0;",
            ),
            block.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
