package com.claudecode.chatplugin.auth

import com.google.gson.JsonParser

/**
 * Whether the Claude Code CLI is signed in, as reported by `claude auth status`.
 *
 * The plugin never handles credentials itself — it reads this status and, when
 * a sign-in is needed, hands off to the CLI's own login flow in a terminal.
 */
sealed interface AuthStatus {

    /** Signed in and usable. */
    data class SignedIn(
        val email: String?,
        val plan: String?,
        val method: String?,
        val organisation: String?
    ) : AuthStatus

    /** The CLI works but nobody is signed in. */
    object SignedOut : AuthStatus

    /** The CLI could not be run at all (not installed, wrong path, timed out). */
    data class Unavailable(val reason: String) : AuthStatus

    companion object {

        /**
         * Parses the JSON `claude auth status` prints. Verified against CLI
         * 2.1.205, which reports:
         *
         *     {"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty",
         *      "email":"…","orgName":"…","subscriptionType":"pro"}
         *
         * Anything unparseable is treated as [Unavailable] rather than as
         * "signed out", so a CLI change can't push people into a sign-in loop
         * when they are in fact signed in.
         */
        fun parse(output: String): AuthStatus {
            val trimmed = output.trim()
            if (trimmed.isEmpty()) return Unavailable("`claude auth status` printed nothing")

            val json = try {
                JsonParser.parseString(trimmed).asJsonObject
            } catch (e: Exception) {
                return Unavailable("Could not read the CLI's auth status: ${trimmed.lineSequence().first()}")
            }

            val loggedIn = json.get("loggedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: return Unavailable("The CLI's auth status did not say whether you are signed in")

            if (!loggedIn) return SignedOut

            fun str(key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
            return SignedIn(
                email = str("email"),
                plan = str("subscriptionType"),
                method = str("authMethod"),
                organisation = str("orgName")
            )
        }
    }
}
