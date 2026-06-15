/**
 * Manages wallet unit lifecycle transitions from candidate through operational, valid, and revoked states.
 */

package di.swallet.wpb.service

import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Creates wallet units and enforces allowed state transitions for activation, validation, and revocation.
 */
@Service
class WalletUnitLifecycleService(
    private val walletUnitRepository: WalletUnitRepository,
) {
    /**
     * Creates a new wallet unit in CANDIDATE state with an optional holder ID.
     */
    fun createCandidate(holderId: String?): WalletUnit =
        walletUnitRepository.save(
            WalletUnit(
                holderId = holderId,
                state = WalletUnitState.CANDIDATE,
                walletId = UUID.randomUUID().toString(),
            ),
        )

    /**
     * Moves a candidate wallet unit to OPERATIONAL after device binding is complete.
     */
    fun activate(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.OPERATIONAL)

    /**
     * Marks an operational wallet unit as VALID after successful credential key binding.
     */
    fun markValid(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.VALID)

    /**
     * Returns the holder's wallet unit when it is eligible for credential issuance.
     */
    fun requireIssuanceEligible(holderId: String): WalletUnit {
        val walletUnit = walletUnitRepository.findFirstByHolderId(holderId)
            .orElseThrow {
                ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wallet unit for holder '$holderId' is not activated (WIAM_07)",
                )
            }
        if (walletUnit.state !in ISSUANCE_ELIGIBLE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Wallet '${walletUnit.walletId}' must be OPERATIONAL or VALID before issuance (current=${walletUnit.state})",
            )
        }
        return walletUnit
    }

    /**
     * Rejects operations when the wallet unit is not OPERATIONAL or VALID.
     */
    fun requireOperational(walletUnit: WalletUnit) {
        if (walletUnit.state !in OPERATIONAL_OR_VALID) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Wallet '${walletUnit.walletId}' is not operational (current=${walletUnit.state})",
            )
        }
    }

    /**
     * Permanently revokes a wallet unit and blocks further lifecycle transitions.
     */
    fun revoke(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.REVOKED)

    /**
     * Persists a wallet unit state change when the transition is allowed by lifecycle rules.
     */
    private fun transition(walletUnit: WalletUnit, target: WalletUnitState): WalletUnit {
        if (!isAllowed(walletUnit.state, target)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Illegal wallet transition ${walletUnit.state} -> $target",
            )
        }
        return walletUnitRepository.save(
            WalletUnit(
                id = walletUnit.id,
                walletId = walletUnit.walletId,
                holderId = walletUnit.holderId,
                state = target,
                createdAt = walletUnit.createdAt,
            ),
        )
    }

    /**
     * Returns whether a lifecycle transition from one state to another is permitted.
     */
    private fun isAllowed(from: WalletUnitState, to: WalletUnitState): Boolean =
        when (from) {
            WalletUnitState.CANDIDATE -> to == WalletUnitState.OPERATIONAL
            WalletUnitState.OPERATIONAL -> to in setOf(WalletUnitState.VALID, WalletUnitState.SUSPENDED, WalletUnitState.REVOKED)
            WalletUnitState.VALID -> to in setOf(WalletUnitState.SUSPENDED, WalletUnitState.REVOKED)
            WalletUnitState.SUSPENDED -> to == WalletUnitState.OPERATIONAL
            else -> false
        }

    companion object {
        private val ISSUANCE_ELIGIBLE = setOf(WalletUnitState.OPERATIONAL, WalletUnitState.VALID)
        private val OPERATIONAL_OR_VALID = ISSUANCE_ELIGIBLE
    }
}
