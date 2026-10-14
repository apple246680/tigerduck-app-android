package org.ntust.app.tigerduck.ui.screen.more

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature

class MoreImplementedFeaturesTest {
    @Test
    fun `More opens exactly the features AppFeature ships, from the one list that decides`() {
        // A separate list here once had to be edited alongside AppFeature.isImplemented; when
        // the two drifted, a feature that worked as a tab read "Coming soon" in More.
        assertEquals(AppFeature.entries.filter { it.isImplemented }.toSet(), implementedFeatures)
    }
}
