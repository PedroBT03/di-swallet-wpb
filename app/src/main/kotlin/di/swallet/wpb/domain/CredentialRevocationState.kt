/**
 * Denormalized revocation state enum stored on wallet credential rows.
 */

package di.swallet.wpb.domain

/** Local credential revocation state mirrored from the status list for reporting. */
enum class CredentialRevocationState {
    ACTIVE,
    REVOKED,
}
