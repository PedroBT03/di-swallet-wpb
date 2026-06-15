/**
 * Tests issuance state.
 */

package di.swallet.wpb.issuance

import di.swallet.wpb.issuance.domain.IssuanceState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IssuanceStateTest {

    /**
     * Each consecutive pair in the standard issuance progression reports canTransitionTo=true.
     */
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

    /**
     * Deferred issuance path transitions are valid; DEFERRED_PENDING may transition to itself.
     */
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

    /**
     * OFFER_RECEIVED cannot jump directly to CREDENTIAL_ISSUED or AUTHORIZED.
     */
    @Test
    fun `cannot skip ahead from OFFER_RECEIVED`() {
        assertFalse(IssuanceState.OFFER_RECEIVED.canTransitionTo(IssuanceState.CREDENTIAL_ISSUED))
        assertFalse(IssuanceState.OFFER_RECEIVED.canTransitionTo(IssuanceState.AUTHORIZED))
    }

    /**
     * NOTIFIED, FAILED, REJECTED, and EXPIRED all report isTerminal=true.
     */
    @Test
    fun `terminal states are flagged as terminal`() {
        listOf(
            IssuanceState.NOTIFIED,
            IssuanceState.FAILED,
            IssuanceState.REJECTED,
            IssuanceState.EXPIRED,
        ).forEach { assertTrue(it.isTerminal, "Expected $it to be terminal") }
    }

    /**
     * Terminal states may move to EXPIRED but not back to CREDENTIAL_REQUESTED.
     */
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

    /**
     * OFFER_RESOLVED may transition directly to AUTHORIZED for pre-authorized flows.
     */
    @Test
    fun `pre-authorized flow can short-circuit prepare step`() {
        assertTrue(IssuanceState.OFFER_RESOLVED.canTransitionTo(IssuanceState.AUTHORIZED))
    }
}
