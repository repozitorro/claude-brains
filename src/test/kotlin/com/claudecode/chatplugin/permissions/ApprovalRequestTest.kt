package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One question, however many times it is asked.
 *
 * Measured against CLI 2.1.232 over HTTP: it abandons an attempt after about a
 * minute, asks about the same call again, four times in all, and ends the turn
 * after roughly four minutes. Every attempt is the same question — so they wait
 * together and are released together, and the user is asked once.
 *
 * This is also why the ten-minute wait the service used to allow was fiction:
 * nobody was still listening by then.
 */
class ApprovalRequestTest {

    private fun request(id: String) =
        ApprovalRequest("Bash", JsonObject(), id, ApprovalSummary("Command npm", "npm test"))

    @Test
    fun `answering releases every attempt waiting on it`() {
        val request = request("toolu_01")
        val released = CountDownLatch(3)
        repeat(3) {
            Thread {
                request.future.get(5, TimeUnit.SECONDS)
                released.countDown()
            }.apply { isDaemon = true }.start()
        }
        request.decide(ApprovalDecision.Allow(null))
        assertTrue("every waiting attempt should be released", released.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a decision made from elsewhere tells whoever drew the card`() {
        // A turn that ends settles this without a click. A card left offering
        // Run to a CLI that has stopped listening invites a click into nothing.
        val request = request("toolu_02")
        val told = CountDownLatch(1)
        request.onDecided = { told.countDown() }
        request.decide(ApprovalDecision.Deny("The turn ended."))
        assertTrue(told.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `the second answer changes nothing`() {
        val request = request("toolu_03")
        assertTrue(request.decide(ApprovalDecision.Allow(null)))
        assertFalse("a later answer must not overwrite the first", request.decide(ApprovalDecision.Deny("no")))
        assertTrue(request.decision is ApprovalDecision.Allow)
    }

    @Test
    fun `a listener that throws does not take the answer with it`() {
        // The answer is owed to a parked CLI thread; a redraw that fails is not
        // a reason to leave it parked.
        val request = request("toolu_04")
        request.onDecided = { error("the panel blew up") }
        assertTrue(request.decide(ApprovalDecision.Allow(null)))
        assertTrue(request.isDecided)
    }
}
