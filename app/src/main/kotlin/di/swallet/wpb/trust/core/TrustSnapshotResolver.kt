package di.swallet.wpb.trust.core

interface TrustSnapshotResolver {
    fun currentAvailability(): TrustSnapshotAvailability
}
