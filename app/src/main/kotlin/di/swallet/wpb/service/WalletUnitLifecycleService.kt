package di.swallet.wpb.service

import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class WalletUnitLifecycleService(
    private val walletUnitRepository: WalletUnitRepository,
) {
    fun createCandidate(holderId: String?): WalletUnit =
        walletUnitRepository.save(
            WalletUnit(
                holderId = holderId,
                state = WalletUnitState.CANDIDATE,
                walletId = UUID.randomUUID().toString(),
            ),
        )

    fun activate(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.OPERATIONAL)

    fun markValid(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.VALID)

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

    fun requireOperational(walletUnit: WalletUnit) {
        if (walletUnit.state !in OPERATIONAL_OR_VALID) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Wallet '${walletUnit.walletId}' is not operational (current=${walletUnit.state})",
            )
        }
    }

    fun revoke(walletUnit: WalletUnit): WalletUnit =
        transition(walletUnit, WalletUnitState.REVOKED)

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
