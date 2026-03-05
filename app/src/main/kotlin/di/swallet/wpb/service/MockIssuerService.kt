package di.swallet.wpb.service

import org.springframework.stereotype.Service

/**
 * Mock implementation of an External Issuer (e.g., Portuguese IRN).
 * Simulates the retrieval of authentic user data for credential issuance.
 */
@Service
class MockIssuerService {

    /**
     * Returns a mock set of user claims.
     * In the real flow (OpenID4VCI), this would be fetched from a secure government database.
     */
    fun fetchUserData(userId: String): Map<String, Any> {
        return mapOf(
            "given_name" to "Pedro",
            "family_name" to "Tavares",
            "birthdate" to "2000-01-01",
            "nationality" to "PT",
            "userId" to userId
        )
    }
}