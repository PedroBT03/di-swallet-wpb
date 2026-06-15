/**
 * Tests presentation state.
 */

package di.swallet.wpb.presentation

import di.swallet.wpb.presentation.domain.PresentationState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresentationStateTest {

    /**
     * Each consecutive state in the RECEIVED-through-DISPATCHED happy path is checked.
     * Every adjacent pair must allow transition.
     */
    @Test
    fun `forward happy-path transitions are valid`() {
        val sequence = listOf(
            PresentationState.RECEIVED,
            PresentationState.REQUEST_RESOLVED,
            PresentationState.VERIFIER_VALIDATED,
            PresentationState.POLICY_EVALUATED,
            PresentationState.CONSENT_PENDING,
            PresentationState.CONSENT_GRANTED,
            PresentationState.VP_BUILT,
            PresentationState.DISPATCHED,
        )
        sequence.windowed(2).forEach { (from, to) ->
            assertTrue(from.canTransitionTo(to), "Expected $from -> $to to be valid")
        }
    }

    /**
     * Session is still in RECEIVED when VP_BUILT is requested.
     * Direct transition is rejected.
     */
    @Test
    fun `cannot skip ahead from RECEIVED to VP_BUILT`() {
        assertFalse(PresentationState.RECEIVED.canTransitionTo(PresentationState.VP_BUILT))
    }

    /**
     * Session has reached DISPATCHED and further moves are attempted.
     * Only EXPIRED is allowed; earlier states are blocked.
     */
    @Test
    fun `dispatched state can only expire afterwards`() {
        val from = PresentationState.DISPATCHED
        assertTrue(from.canTransitionTo(PresentationState.EXPIRED))
        assertFalse(from.canTransitionTo(PresentationState.REQUEST_RESOLVED))
        assertFalse(from.canTransitionTo(PresentationState.VP_BUILT))
    }

    /**
     * FAILED, REJECTED, DISPATCHED, and EXPIRED are enumerated.
     * Each must report isTerminal as true.
     */
    @Test
    fun `every terminal state is flagged as terminal`() {
        listOf(
            PresentationState.FAILED,
            PresentationState.REJECTED,
            PresentationState.DISPATCHED,
            PresentationState.EXPIRED,
        ).forEach { assertTrue(it.isTerminal, "Expected $it to be terminal") }
    }
}
