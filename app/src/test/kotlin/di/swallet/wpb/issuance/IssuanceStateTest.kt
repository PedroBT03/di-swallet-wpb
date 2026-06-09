package di.swallet.wpb.issuance

import di.swallet.wpb.issuance.domain.IssuanceState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IssuanceStateTest {

    @Test
    fun `forward happy-path transitions are valid`() {
        val happyPath = listOf(
            IssuanceState.OFFER_RECEIVED,
            IssuanceState.OFFER_RESOLVED,
            IssuanceState.AUTHORIZATION_PREPARED,
            IssuanceState.AUTHORIZED,
            IssuanceState.CREDENTIAL_REQUESTED,
            IssuanceState.ISSUANCE_CONSENT_PENDING,
            IssuanceState.CREDENTIAL_ISSUED,
            IssuanceState.NOTIFIED,
        )
        happyPath.windowed(2).forEach { (from, to) ->
            assertTrue(from.canTransitionTo(to), "Expected $from -> $to to be valid")
        }
    }

    @Test
    fun `deferred branch transitions are valid`() {
        val deferredPath = listOf(
            IssuanceState.CREDENTIAL_REQUESTED,
            IssuanceState.DEFERRED_PENDING,
            IssuanceState.ISSUANCE_CONSENT_PENDING,
            IssuanceState.DEFERRED_ISSUED,
            IssuanceState.NOTIFIED,
        )
        deferredPath.windowed(2).forEach { (from, to) ->
            assertTrue(from.canTransitionTo(to), "Expected $from -> $to to be valid")
        }
        assertTrue(IssuanceState.DEFERRED_PENDING.canTransitionTo(IssuanceState.DEFERRED_PENDING))
    }

    @Test
    fun `cannot skip ahead from OFFER_RECEIVED`() {
        assertFalse(IssuanceState.OFFER_RECEIVED.canTransitionTo(IssuanceState.CREDENTIAL_ISSUED))
        assertFalse(IssuanceState.OFFER_RECEIVED.canTransitionTo(IssuanceState.AUTHORIZED))
    }

    @Test
    fun `terminal states are flagged as terminal`() {
        listOf(
            IssuanceState.NOTIFIED,
            IssuanceState.FAILED,
            IssuanceState.REJECTED,
            IssuanceState.EXPIRED,
        ).forEach { assertTrue(it.isTerminal, "Expected $it to be terminal") }
    }

    @Test
    fun `terminal states can only expire afterwards`() {
        listOf(
            IssuanceState.NOTIFIED,
            IssuanceState.FAILED,
            IssuanceState.REJECTED,
        ).forEach { terminal ->
            assertTrue(terminal.canTransitionTo(IssuanceState.EXPIRED))
            assertFalse(
                terminal.canTransitionTo(IssuanceState.CREDENTIAL_REQUESTED),
                "$terminal should not transition back",
            )
        }
    }

    @Test
    fun `pre-authorized flow can short-circuit prepare step`() {
        assertTrue(IssuanceState.OFFER_RESOLVED.canTransitionTo(IssuanceState.AUTHORIZED))
    }
}
