/**
 * OID4VP handover inputs used when building mdoc session transcripts.
 */

package di.swallet.wpb.format.mdoc

/** Inputs required to build an OID4VP SessionTranscript for mdoc presentation. */
data class MdocOpenId4VpHandover(
    val clientId: String,
    val nonce: String,
    val audience: String,
    val responseUri: String? = null,
    val verifierEncryptionJwkThumbprint: ByteArray? = null,
)
