/**
 * Dev-only mock external issuer that returns sample identity claims for credential issuance demos.
 */

package di.swallet.wpb.service

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Simulates an external government issuer by returning fixed demo user claims in the dev profile.
 */
@Service
@Profile("dev")
class MockIssuerService {

    /**
     * Returns a static set of PID-like claims for the given user ID.
     */
    fun fetchUserData(userId: String): Map<String, Any> = DemoAttestationClaims.pidClaims(userId)
}
