package org.peekit.opentimelapse.core.net

/**
 * The parsed head of one HTTP request - the only parts the control server acts on.
 * Pure, so the parsing that guards every request is unit-tested rather than trusted.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val authorization: String?,
) {
    companion object {

        /** Null for anything that is not a plausible request line. */
        fun parse(requestLine: String?, headerLines: List<String>): HttpRequest? {
            if (requestLine.isNullOrBlank()) return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null

            val authorization = headerLines
                .firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()

            return HttpRequest(method = parts[0], path = parts[1], authorization = authorization)
        }
    }
}
