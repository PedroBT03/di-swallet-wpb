/**
 * Wallet Instance Attestation (WIA) status list allocation and revocation.
 */

package di.swallet.wpb.wia.status

import di.swallet.wpb.issuance.domain.WiaStatusReference

/** Manages status list indices referenced by wallet instance attestations. */
interface WiaStatusManagementService {
    /** Returns an existing or newly allocated status list entry for the holder and issuer scope. */
    fun getOrAllocateStatus(holderId: String, issuerId: String?): WiaStatusReference

    /** Marks all status indices associated with the holder as revoked. */
    fun revokeHolder(holderId: String)
}
