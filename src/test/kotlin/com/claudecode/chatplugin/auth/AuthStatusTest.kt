package com.claudecode.chatplugin.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStatusTest {

    @Test
    fun `reads a real signed-in response`() {
        // Captured verbatim from `claude auth status` on CLI 2.1.205.
        val json = """
            {
              "loggedIn": true,
              "authMethod": "claude.ai",
              "apiProvider": "firstParty",
              "email": "someone@example.com",
              "orgId": "d996bf86-7a12-441e-92d2-2b24e5a9ec96",
              "orgName": "Someone's Organization",
              "subscriptionType": "pro"
            }
        """.trimIndent()

        val status = AuthStatus.parse(json)

        assertTrue(status is AuthStatus.SignedIn)
        status as AuthStatus.SignedIn
        assertEquals("someone@example.com", status.email)
        assertEquals("pro", status.plan)
        assertEquals("claude.ai", status.method)
    }

    @Test
    fun `signed out is reported as such`() {
        assertEquals(AuthStatus.SignedOut, AuthStatus.parse("""{"loggedIn": false}"""))
    }

    @Test
    fun `a signed-in response missing the optional details still counts`() {
        val status = AuthStatus.parse("""{"loggedIn": true}""")

        assertTrue(status is AuthStatus.SignedIn)
        assertEquals(null, (status as AuthStatus.SignedIn).email)
    }

    @Test
    fun `unreadable output is unavailable, never signed out`() {
        // Treating these as signed-out would push a signed-in user into a
        // sign-in loop they cannot escape.
        listOf(
            "",
            "   ",
            "claude: command not found",
            """{"unexpected": "shape"}""",
            "<html>proxy error</html>"
        ).forEach { output ->
            assertTrue(output, AuthStatus.parse(output) is AuthStatus.Unavailable)
        }
    }
}
