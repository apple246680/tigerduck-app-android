package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** A [StoredPasswordLatch] in memory, for tests that need no persisted one. */
internal class InMemoryStoredPasswordLatch : StoredPasswordLatch {
    private val state = MutableStateFlow<StoredPasswordLatch.Rejection?>(null)
    override val rejection: StateFlow<StoredPasswordLatch.Rejection?> = state.asStateFlow()

    override var generation = 0
        private set

    override val automaticPosts = Mutex()

    override fun markRejected(generation: Int, studentId: String, password: String, reason: String?) {
        if (generation == this.generation && state.value == null) state.value = StoredPasswordLatch.Rejection(reason)
    }

    override fun clear() {
        generation++
        state.value = null
    }
}
