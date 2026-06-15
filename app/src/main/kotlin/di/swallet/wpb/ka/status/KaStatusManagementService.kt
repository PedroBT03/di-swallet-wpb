/**
 * Key Attestation (KA) status list allocation and revocation.
 */

package di.swallet.wpb.ka.status

import di.swallet.wpb.issuance.domain.KaStatusReference

/** Manages status list indices referenced by key attestations. */
interface KaStatusManagementService {
    /** Returns an existing or newly allocated status entry for holder, issuer scope, and attestation fingerprint. */
    fun getOrAllocateStatus(holderId: String, issuerId: String?, attestationFingerprint: String): KaStatusReference

    /** Marks all status indices associated with the holder as revoked. */
    fun revokeHolder(holderId: String)
}
