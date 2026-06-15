/**
 * Contract for components that expose the current trust snapshot availability.
 */

package di.swallet.wpb.trust.core

/** Supplies the latest trust snapshot or explains why it is unavailable. */
interface TrustSnapshotResolver {
    /** Returns whether a trust snapshot is currently available. */
    fun currentAvailability(): TrustSnapshotAvailability
}
