/**
 * Jackson serializer for Yubico WebAuthn ByteArray values.
 */

package di.swallet.wpb.security

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

/**
 * Writes Yubico WebAuthn byte arrays as base64url strings in JSON.
 */
class WebAuthnByteArraySerializer : JsonSerializer<com.yubico.webauthn.data.ByteArray>() {
    /**
     * Encodes the WebAuthn byte array as a base64url JSON string.
     */
    override fun serialize(
        value: com.yubico.webauthn.data.ByteArray,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        gen.writeString(value.base64Url)
    }
}
