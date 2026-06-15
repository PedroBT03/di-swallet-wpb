package di.swallet.wpb.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.yubico.webauthn.AssertionRequest

object AssertionRequestCodec {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(Jdk8Module())
        .registerModule(
            SimpleModule().apply {
                addSerializer(com.yubico.webauthn.data.ByteArray::class.java, WebAuthnByteArraySerializer())
                addDeserializer(com.yubico.webauthn.data.ByteArray::class.java, WebAuthnByteArrayDeserializer())
            },
        )

    fun encode(request: AssertionRequest): String = objectMapper.writeValueAsString(request)

    fun decode(json: String): AssertionRequest = objectMapper.readValue(json)
}
