package di.swallet.wpb.format.mdoc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MdocCredentialCodecTest {

    private val codec = MdocCredentialCodec(MdocIsoRuntimeService())

    @Test
    fun `encodes and decodes PID vector`() {
        val doc = MdocCredentialDocument(
            docType = "eu.europa.ec.eudi.pid.1",
            namespace = "eu.europa.ec.eudi.pid.1",
            claims = mapOf(
                "given_name" to "Alice",
                "family_name" to "Doe",
                "birth_date" to "1990-01-01",
                "nationalities" to listOf("PT"),
            ),
            issuer = "https://issuer.example",
            issuedAtEpochSeconds = 1_780_410_000,
        )
        val encoded = codec.encode(doc)
        assertTrue(codec.validateIssuerSigned(encoded))
        val decoded = codec.decode(encoded)!!
        assertEquals("unknown", decoded.docType)
        assertEquals(doc.namespace, decoded.namespace)
        assertEquals("Alice", decoded.claims["eu.europa.ec.eudi.pid.1.given_name"])
    }

    @Test
    fun `encodes and decodes mDL vector`() {
        val doc = MdocCredentialDocument(
            docType = "org.iso.18013.5.1.mDL",
            namespace = "org.iso.18013.5.1",
            claims = mapOf(
                "given_name" to "Alice",
                "family_name" to "Doe",
                "birth_date" to "1990-01-01",
                "driving_privileges" to listOf("B"),
            ),
            issuer = "https://issuer.example",
            issuedAtEpochSeconds = 1_780_410_001,
        )
        val encoded = codec.encode(doc)
        val decoded = codec.decode(encoded)!!
        assertEquals("unknown", decoded.docType)
        assertEquals("org.iso.18013.5.1", decoded.namespace)
        assertEquals(listOf("B"), decoded.claims["org.iso.18013.5.1.driving_privileges"])
    }
}
