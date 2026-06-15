/**
 * Loads and refreshes verifier trust snapshots from local and remote sources.
 */

package di.swallet.wpb.presentation.trust

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically refreshes remote trust material when remote or hybrid mode is enabled.
 */
@Component
class TrustSnapshotRefreshScheduler(
    private val trustSnapshotService: TrustSnapshotService,
) {
    /**
     * Triggers a remote trust refresh on the configured interval.
     */
    @Scheduled(fixedDelayString = "\${wpb.openid4vp.trust.remote-refresh-interval-seconds:900}000")
    fun refreshRemoteTrustSnapshot() {
        trustSnapshotService.refreshRemoteIfConfigured()
    }
}
