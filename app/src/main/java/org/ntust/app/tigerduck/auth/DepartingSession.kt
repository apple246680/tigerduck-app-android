package org.ntust.app.tigerduck.auth

/**
 * The backend session of an account that signed out, taken before [AuthTokenManager.logout]
 * wiped it. Only the sign-out's device unregister goes out with it.
 *
 * Not a data class: its toString() would print the tokens.
 */
class DepartingSession(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] expires, epoch ms. */
    val accessExpiresAt: Long,
)

/** What [AuthTokenManager.refreshDeparting] got back. */
sealed interface DepartingRefresh {
    /** Not a data class, for the same reason as [DepartingSession]. */
    class Refreshed(val session: DepartingSession) : DepartingRefresh

    /** Turned down: the session is over on the server, and nothing can go out as it any more. */
    data object Refused : DepartingRefresh

    /** A 429 or a 5xx, or no reply: worth trying again later with the same token. */
    data object Unavailable : DepartingRefresh
}
