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
     * Activates a candidate wallet unit to VALID after identity establishment / first issuance.
     */
    fun markValid(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.VALID)

    /**
     * Returns the holder's wallet unit when it is eligible for credential issuance.
     *
     * A CANDIDATE wallet is eligible: an anonymous, provisioned wallet may obtain its first
     * credential (PID via CMD), which then promotes it to VALID. A VALID wallet stays eligible.
     */
    fun requireIssuanceEligible(holderId: String): WalletUnit {
        val walletUnit = walletUnitRepository.findFirstByHolderId(holderId)
            .orElseThrow {
                ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wallet unit for holder '$holderId' is not provisioned (WIAM_07)",
                )
            }
        if (walletUnit.state !in ISSUANCE_ELIGIBLE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Wallet '${walletUnit.walletId}' must be CANDIDATE or VALID before issuance (current=${walletUnit.state})",
            )
        }
        return walletUnit
    }

    /**
     * Returns the holder's wallet unit when holder-facing signing is allowed (VALID only).
     */
    fun requireSignCapable(holderId: String): WalletUnit {
        val walletUnit = walletUnitRepository.findFirstByHolderId(holderId)
            .orElseThrow {
                ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wallet unit for holder '$holderId' is not provisioned",
                )
            }
        if (walletUnit.state != WalletUnitState.VALID) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Wallet '${walletUnit.walletId}' must be VALID before holder signing (current=${walletUnit.state})",
            )
        }
        return walletUnit
    }

    /**
     * Permanently revokes a wallet unit and blocks further lifecycle transitions.
     */
    fun revoke(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.REVOKED)

    /**
     * Temporarily blocks a valid wallet unit.
     */
    fun suspend(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.SUSPENDED)

    /**
     * Restores a suspended wallet unit to VALID.
     */
    fun unsuspend(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.VALID)

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
            WalletUnitState.CANDIDATE -> to in setOf(WalletUnitState.VALID, WalletUnitState.REVOKED, WalletUnitState.DELETED)
            WalletUnitState.VALID -> to in setOf(WalletUnitState.SUSPENDED, WalletUnitState.REVOKED, WalletUnitState.DELETED)
            WalletUnitState.SUSPENDED -> to in setOf(WalletUnitState.VALID, WalletUnitState.REVOKED, WalletUnitState.DELETED)
            WalletUnitState.REVOKED -> to == WalletUnitState.DELETED
            WalletUnitState.DELETED -> false
        }

    companion object {
        private val ISSUANCE_ELIGIBLE = setOf(WalletUnitState.CANDIDATE, WalletUnitState.VALID)
    }
}
