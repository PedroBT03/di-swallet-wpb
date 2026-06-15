/**
 * Shared test helpers for sd jwt.
 */

package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper

object SdJwtTestFixtures {
    /** Wires SdJwtService and SdJwtDisclosureSelector sharing the same ObjectMapper for SD-JWT tests. */
    fun services(objectMapper: ObjectMapper = ObjectMapper()): Pair<SdJwtService, SdJwtDisclosureSelector> {
        val sdJwtService = SdJwtService(objectMapper)
        val selector = SdJwtDisclosureSelector(objectMapper, sdJwtService)
        return sdJwtService to selector
    }
}
