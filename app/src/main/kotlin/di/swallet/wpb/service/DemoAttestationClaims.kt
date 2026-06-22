/**
 * Demo PID and mDL claim sets aligned with ARF PID Rulebook (CIR 2024/2977) subsets.
 */

package di.swallet.wpb.service

/** Static demo attestation payloads shared by mock issuance and the OID4VCI simulator. */
object DemoAttestationClaims {

    /**
     * SD-JWT PID claims: mandatory CIR attributes plus mandatory metadata (SD-JWT claim names).
     * Optional demo address fields are included for selective-disclosure exercises.
     */
    fun pidClaims(holderId: String): Map<String, Any> = mapOf(
        "given_name" to "Pedro",
        "family_name" to "Tavares",
        "birthdate" to "2000-01-01",
        "place_of_birth" to mapOf(
            "country" to "PT",
            "locality" to "Lisbon",
        ),
        "nationalities" to listOf("PT", "ES"),
        "address" to mapOf(
            "locality" to "Lisbon",
            "country" to "PT",
        ),
        "date_of_expiry" to "2031-12-31",
        "issuing_authority" to "República Portuguesa",
        "issuing_country" to "PT",
        "holder_id" to holderId,
    )

    /** ISO 18013-5 mDL namespace claims for simulator issuance. */
    fun mdlClaims(): Map<String, Any> = mapOf(
        "given_name" to "Pedro",
        "family_name" to "Tavares",
        "birth_date" to "2000-01-01",
        "driving_privileges" to listOf("B"),
        "issuing_country" to "PT",
    )
}
