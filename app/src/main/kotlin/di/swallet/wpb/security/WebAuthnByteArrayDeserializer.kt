/**
 * Jackson deserializer for Yubico WebAuthn ByteArray values.
 */

package di.swallet.wpb.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

/**
 * Reads base64url JSON strings back into Yubico WebAuthn byte arrays.
 */
class WebAuthnByteArrayDeserializer : JsonDeserializer<com.yubico.webauthn.data.ByteArray>() {
    /**
     * Decodes a base64url JSON string into a WebAuthn byte array.
     */
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): com.yubico.webauthn.data.ByteArray =
        com.yubico.webauthn.data.ByteArray.fromBase64Url(parser.valueAsString)
}
