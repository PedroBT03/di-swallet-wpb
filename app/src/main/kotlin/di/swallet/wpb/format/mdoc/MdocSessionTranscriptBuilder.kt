/**
 * CBOR SessionTranscript builder for legacy and OpenID4VP mdoc handover modes.
 */

package di.swallet.wpb.format.mdoc

import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORNull
import com.authlete.cbor.CBORPair
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import di.swallet.wpb.config.MdocProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest

/** Builds ISO mdoc SessionTranscript CBOR for legacy aud/nonce or OpenID4VP handover. */
@Component
class MdocSessionTranscriptBuilder(
    private val properties: MdocProperties,
) {
    /** Produces the SessionTranscript CBOR item for the resolved handover mode. */
    fun buildSessionTranscript(handover: MdocOpenId4VpHandover): CBORItem =
        when (resolveMode(handover)) {
            TranscriptMode.LEGACY_AUD_NONCE -> legacyAudNonceTranscript(handover)
            TranscriptMode.OPENID4VP -> openId4VpTranscript(handover)
        }

    /** Chooses legacy or OpenID4VP transcript layout from configuration and handover inputs. */
    private fun resolveMode(handover: MdocOpenId4VpHandover): TranscriptMode =
        when (properties.sessionTranscriptModeNormalized()) {
            "openid4vp" -> TranscriptMode.OPENID4VP
            "hybrid" -> if (!handover.responseUri.isNullOrBlank()) {
                TranscriptMode.OPENID4VP
            } else {
                TranscriptMode.LEGACY_AUD_NONCE
            }
            else -> TranscriptMode.LEGACY_AUD_NONCE
        }

    /** Legacy transcript encoding audience and nonce as CBOR string pairs. */
    private fun legacyAudNonceTranscript(handover: MdocOpenId4VpHandover): CBORItem =
        CBORPairList(
            CBORPair(CBORString("aud"), CBORString(handover.audience)),
            CBORPair(CBORString("nonce"), CBORString(handover.nonce)),
        )

    /** OpenID4VP handover transcript with SHA-256 digest over client info bytes. */
    private fun openId4VpTranscript(handover: MdocOpenId4VpHandover): CBORItem {
        val info = CBORItemList(
            CBORString(handover.clientId),
            CBORString(handover.nonce),
            handover.verifierEncryptionJwkThumbprint?.let { CBORByteArray(it) } ?: CBORNull.INSTANCE,
            CBORString(handover.responseUri.orEmpty()),
        )
        val infoBytes = info.encode()
        val digest = MessageDigest.getInstance("SHA-256").digest(infoBytes)
        val openId4VpHandover = CBORItemList(
            CBORString("OpenID4VPHandover"),
            CBORByteArray(digest),
        )
        return CBORItemList(CBORNull.INSTANCE, CBORNull.INSTANCE, openId4VpHandover)
    }

    private enum class TranscriptMode {
        LEGACY_AUD_NONCE,
        OPENID4VP,
    }
}
