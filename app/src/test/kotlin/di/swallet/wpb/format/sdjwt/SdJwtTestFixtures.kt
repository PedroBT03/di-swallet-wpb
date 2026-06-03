package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper

object SdJwtTestFixtures {
    fun services(objectMapper: ObjectMapper = ObjectMapper()): Pair<SdJwtService, SdJwtDisclosureSelector> {
        val sdJwtService = SdJwtService(objectMapper)
        val selector = SdJwtDisclosureSelector(objectMapper, sdJwtService)
        return sdJwtService to selector
    }
}
