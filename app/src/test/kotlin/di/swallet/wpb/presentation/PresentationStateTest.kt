package di.swallet.wpb.presentation

import di.swallet.wpb.presentation.domain.PresentationState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PresentationStateTest {
    @Test
    fun `allowed transition path should be valid`() {
        var state = PresentationState.RECEIVED
        assertTrue(state.canTransitionTo(PresentationState.REQUEST_RESOLVED))
        state = PresentationState.REQUEST_RESOLVED
        assertTrue(state.canTransitionTo(PresentationState.VERIFIER_VALIDATED))
        state = PresentationState.VERIFIER_VALIDATED
        assertTrue(state.canTransitionTo(PresentationState.POLICY_EVALUATED))
        state = PresentationState.POLICY_EVALUATED
        assertTrue(state.canTransitionTo(PresentationState.CONSENT_PENDING))
        state = PresentationState.CONSENT_PENDING
        assertTrue(state.canTransitionTo(PresentationState.CONSENT_GRANTED))
        state = PresentationState.CONSENT_GRANTED
        assertTrue(state.canTransitionTo(PresentationState.VP_BUILT))
        state = PresentationState.VP_BUILT
        assertTrue(state.canTransitionTo(PresentationState.DISPATCHED))
    }

    @Test
    fun `invalid transition should be rejected`() {
        val from = PresentationState.RECEIVED
        assertFalse(from.canTransitionTo(PresentationState.VP_BUILT))
    }
}
