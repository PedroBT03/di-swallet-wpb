package di.swallet.wpb.security

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

class WebAuthnByteArraySerializer : JsonSerializer<com.yubico.webauthn.data.ByteArray>() {
    override fun serialize(
        value: com.yubico.webauthn.data.ByteArray,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        gen.writeString(value.base64Url)
    }
}
