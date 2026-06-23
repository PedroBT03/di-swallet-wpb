/**
 * Tests sd jwt disclosure selector.
 */

package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.presentation.domain.ClaimPathSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdJwtDisclosureSelectorTest {

    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val selector = SdJwtDisclosureSelector(objectMapper, sdJwtService)

    /**
     * Two disclosures for given_name and country; selecting given_name returns only that disclosure.
     */
    @Test
    fun `selects disclosure by leaf key`() {
        val given = sdJwtService.createDisclosure("given_name", "Pedro")
        val country = sdJwtService.createDisclosure("country", "PT")
        val selected = selector.selectDisclosures(
            listOf(given, country),
            listOf(ClaimPath.key("given_name")),
        )
        assertEquals(listOf(given), selected)
    }

    /**
     * address.locality and address.country disclosures; path address.locality selects locality only.
     */
    @Test
    fun `selects disclosure by PID dot notation path`() {
        val locality = sdJwtService.createDisclosure("address.locality", "Lisbon")
        val country = sdJwtService.createDisclosure("address.country", "PT")
        val path = ClaimPath(listOf(ClaimPathSegment.Key("address"), ClaimPathSegment.Key("locality")))
        val selected = selector.selectDisclosures(listOf(locality, country), listOf(path))
        assertEquals(listOf(locality), selected)
    }

    /**
     * Empty requested paths yield an empty disclosure list even when disclosures exist.
     */
    @Test
    fun `empty paths selects no disclosures`() {
        val disc = sdJwtService.createDisclosure("given_name", "Pedro")
        assertTrue(selector.selectDisclosures(listOf(disc), emptyList()).isEmpty())
    }

    /**
     * canSatisfy is true for a matching single path and false when any requested path is missing.
     */
    @Test
    fun `canSatisfy requires every path to match`() {
        val given = sdJwtService.createDisclosure("given_name", "Pedro")
        assertTrue(selector.canSatisfy(listOf(given), listOf(ClaimPath.key("given_name"))))
        assertFalse(
            selector.canSatisfy(
                listOf(given),
                listOf(ClaimPath.key("given_name"), ClaimPath.key("family_name")),
            ),
        )
    }

    /**
     * nationalities disclosure with wildcard path segment is selected and satisfies canSatisfy.
     */
    @Test
    fun `satisfies array path with wildcard segment`() {
        val disc = sdJwtService.createDisclosure("nationalities", listOf("PT", "ES"))
        val path = ClaimPath(listOf(ClaimPathSegment.Key("nationalities"), ClaimPathSegment.Wildcard))
        assertTrue(selector.canSatisfy(listOf(disc), listOf(path)))
        assertEquals(listOf(disc), selector.selectDisclosures(listOf(disc), listOf(path)))
    }

    /**
     * nationalities disclosure with index segment [1] satisfies canSatisfy.
     */
    @Test
    fun `satisfies array path with index segment`() {
        val disc = sdJwtService.createDisclosure("nationalities", listOf("PT", "ES"))
        val path = ClaimPath(listOf(ClaimPathSegment.Key("nationalities"), ClaimPathSegment.Index(1)))
        assertTrue(selector.canSatisfy(listOf(disc), listOf(path)))
    }

    /**
     * Nested address object; selecting address.locality returns parent and child disclosures (closure of two).
     */
    @Test
    fun `nested SD-JWT path selects child and parent closure`() {
        val issued = sdJwtService.createNestedObjectDisclosures(
            "address",
            mapOf("locality" to "Lisbon", "country" to "PT"),
        )
        val path = ClaimPath(listOf(ClaimPathSegment.Key("address"), ClaimPathSegment.Key("locality")))
        assertTrue(selector.canSatisfy(issued.disclosures, listOf(path)))
        val selected = selector.selectDisclosures(issued.disclosures, listOf(path))
        assertEquals(2, selected.size)
        val names = selector.parseDisclosures(selected).map { it.claimName }.toSet()
        assertEquals(setOf("address", "locality"), names)
    }

    /**
     * Homonymous nested leaves (country/locality) under address vs place_of_birth must not cross-match.
     */
    @Test
    fun `nested path selects only leaves under the requested parent object`() {
        val address = sdJwtService.createNestedObjectDisclosures(
            "address",
            mapOf("locality" to "Lisbon", "country" to "PT"),
        )
        val placeOfBirth = sdJwtService.createNestedObjectDisclosures(
            "place_of_birth",
            mapOf("locality" to "Lisbon", "country" to "PT"),
        )
        val given = sdJwtService.createDisclosure("given_name", "Pedro")
        val issuing = sdJwtService.createDisclosure("issuing_country", "PT")
        val stored = address.disclosures + placeOfBirth.disclosures + listOf(given, issuing)

        val paths = listOf(
            ClaimPath.fromDotNotation("address.locality"),
            ClaimPath.fromDotNotation("address.country"),
            ClaimPath.key("given_name"),
            ClaimPath.key("issuing_country"),
        )
        val selected = selector.selectDisclosures(stored, paths)
        val parsed = selector.parseDisclosures(selected)

        assertFalse(parsed.any { it.claimName == "place_of_birth" })
        assertEquals(1, parsed.count { it.claimName == "locality" })
        assertEquals(1, parsed.count { it.claimName == "country" })
        assertTrue(parsed.any { it.claimName == "address" })
        assertTrue(parsed.any { it.claimName == "given_name" })
        assertTrue(parsed.any { it.claimName == "issuing_country" })
    }
}
