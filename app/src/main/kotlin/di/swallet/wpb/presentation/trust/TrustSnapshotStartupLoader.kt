package di.swallet.wpb.presentation.trust

import di.swallet.wpb.trust.core.TrustSnapshotAvailability
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Eagerly loads the trust snapshot at startup so actuator health and early
 * presentation requests see cached LoTE material without waiting for the scheduler.
 */
@Component
class TrustSnapshotStartupLoader(
    private val trustSnapshotService: TrustSnapshotService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        when (val availability = trustSnapshotService.refresh()) {
            is TrustSnapshotAvailability.Available ->
                logger.info("event=trust.snapshot.startup_loaded source={}", availability.snapshot.source)
            is TrustSnapshotAvailability.Unavailable ->
                logger.warn("event=trust.snapshot.startup_unavailable reason={}", availability.reason)
        }
    }
}
