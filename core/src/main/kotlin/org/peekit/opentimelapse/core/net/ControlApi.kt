package org.peekit.opentimelapse.core.net

import kotlinx.serialization.Serializable

/**
 * The control server, as pure logic. The Android side owns only the socket, the threads and
 * the mDNS registration; every decision about what a request is allowed to do, and what a
 * response says, is made here so it can be unit-tested without a network.
 */

/** What the phone is doing, as reported to a watching laptop. */
enum class ServerState { IDLE, RUNNING }

/**
 * A snapshot of the session for `GET /status`. Serialized to JSON as-is, so the field names
 * are the wire format - rename with care.
 */
@Serializable
data class StatusSnapshot(
    val state: ServerState,
    val sessionName: String? = null,
    val framesCaptured: Int = 0,
    val nextFrameInSeconds: Int? = null,
    val intervalSeconds: Int = 0,
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val freeStorageMb: Long = 0,
    /** Millis since the session started, so a client can show an elapsed clock. */
    val runningForMs: Long = 0,
)

/** The endpoints the server answers. Anything else is a 404. */
enum class Route(val method: String, val path: String) {
    PAGE("GET", "/"),
    STATUS("GET", "/status"),
    PREVIEW("GET", "/preview"),
    STOP("POST", "/stop");

    companion object {
        fun match(method: String, path: String): Route? {
            // Query string is the token's carrier for GETs from a browser; ignore it here.
            val bare = path.substringBefore('?')
            return entries.firstOrNull { it.method == method && it.path == bare }
        }
    }
}

/** The verdict for one request, decided before any work is done. */
enum class Access {
    /** Serve it. */
    ALLOW,

    /** The static shell page, served without a token so the client has somewhere to paste it. */
    ALLOW_PUBLIC,

    /** Known route, wrong or missing token. */
    UNAUTHORIZED,

    /** No such route. */
    NOT_FOUND,

    /** Method not allowed on a path that exists for another method. */
    BAD_METHOD,
}

/**
 * The gatekeeper. Given the configured token and what a request presented, decides whether it
 * may proceed - before the server does anything a request could observe.
 *
 * Only the shell page at `/` is public; it carries no session data and exists so a browser has
 * a place to enter the token. Everything else - status, preview, control, the event stream -
 * needs the token, because a frame preview is camera output and a stop is control of the shoot.
 */
object ControlAuth {

    fun evaluate(
        configuredToken: String,
        method: String,
        path: String,
        presentedToken: String?,
    ): Access {
        val route = Route.match(method, path)
        if (route != null) {
            if (route == Route.PAGE) return Access.ALLOW_PUBLIC
            return if (tokenMatches(configuredToken, presentedToken)) Access.ALLOW else Access.UNAUTHORIZED
        }

        // A path that exists for a different method is a 405, not a 404 - it tells an honest
        // client it used the wrong verb without revealing anything to a hostile one.
        val bare = path.substringBefore('?')
        val pathExists = Route.entries.any { it.path == bare }
        return if (pathExists) Access.BAD_METHOD else Access.NOT_FOUND
    }

    /**
     * Pulls the token a request presented, from either an `Authorization: Bearer <t>` header
     * (how a script sends it) or a `?token=<t>` query parameter (how a browser link carries
     * it). The header wins if both are present.
     */
    fun presentedToken(authorizationHeader: String?, path: String): String? {
        // Strip the scheme case-insensitively, then trim - trimming first would eat the very
        // space the "Bearer " prefix needs and leave the scheme word behind as the "token".
        val fromHeader = authorizationHeader
            ?.let { if (it.startsWith("Bearer", ignoreCase = true)) it.substring("Bearer".length) else it }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromHeader != null) return fromHeader

        val query = path.substringAfter('?', "")
        return query.split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Constant-time comparison. A `==` on the token would leak its length and its matching
     * prefix through timing; over a LAN that is a real oracle. The cost is one pass over a
     * short string, which is nothing.
     */
    fun tokenMatches(configured: String, presented: String?): Boolean {
        if (configured.isEmpty()) return false
        if (presented == null) return false
        if (presented.length != configured.length) return false
        var diff = 0
        for (i in configured.indices) diff = diff or (configured[i].code xor presented[i].code)
        return diff == 0
    }
}
