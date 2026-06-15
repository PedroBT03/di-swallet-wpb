package di.swallet.wpb.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

class WebAuthnByteArrayDeserializer : JsonDeserializer<com.yubico.webauthn.data.ByteArray>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): com.yubico.webauthn.data.ByteArray =
        com.yubico.webauthn.data.ByteArray.fromBase64Url(parser.valueAsString)
}
