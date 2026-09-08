/**
 * Eager trust snapshot loading at application startup.
 */

package di.swallet.wpb.presentation.trust

import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Loads trust material during startup so health checks and early requests see cached LoTE data.
 */
@Component
class TrustSnapshotStartupLoader(
    private val trustSnapshotService: TrustSnapshotService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Refreshes the trust snapshot once at startup and logs whether loading succeeded.
     */
    override fun run(args: ApplicationArguments) {
        when (val availability = trustSnapshotService.refresh()) {
            is TrustSnapshotAvailability.Available ->
                logger.info("event=trust.snapshot.startup_loaded source={}", availability.snapshot.source)
            is TrustSnapshotAvailability.Unavailable ->
                logger.warn("event=trust.snapshot.startup_unavailable reason={}", availability.reason)
        }
    }
}
