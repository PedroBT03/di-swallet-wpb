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
    fun fetchUserData(userId: String): Map<String, Any> {
        return mapOf(
            "given_name" to "Pedro",
            "family_name" to "Tavares",
            "birthdate" to "2000-01-01",
            "nationality" to "PT",
            "nationalities" to listOf("PT", "ES"),
            "address" to mapOf(
                "locality" to "Lisbon",
                "country" to "PT",
            ),
            "userId" to userId,
        )
    }
}
