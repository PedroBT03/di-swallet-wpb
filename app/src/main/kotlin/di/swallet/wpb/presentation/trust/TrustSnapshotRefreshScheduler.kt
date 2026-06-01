package di.swallet.wpb.presentation.trust

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TrustSnapshotRefreshScheduler(
    private val trustSnapshotService: TrustSnapshotService,
) {
    @Scheduled(fixedDelayString = "\${wpb.openid4vp.trust.remote-refresh-interval-seconds:900}000")
    fun refreshRemoteTrustSnapshot() {
        trustSnapshotService.refreshRemoteIfConfigured()
    }
}
