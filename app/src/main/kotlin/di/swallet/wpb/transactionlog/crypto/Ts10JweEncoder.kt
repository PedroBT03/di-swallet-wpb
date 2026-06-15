package di.swallet.wpb.transactionlog.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.PasswordBasedDecrypter
import com.nimbusds.jose.crypto.PasswordBasedEncrypter
import di.swallet.wpb.transactionlog.domain.Ts10MigrationData
import di.swallet.wpb.transactionlog.domain.Ts10TransactionLogExport
import org.springframework.stereotype.Component

@Component
class Ts10JweEncoder(
    private val objectMapper: ObjectMapper,
) {
    fun encryptTransactionLogExport(export: Ts10TransactionLogExport, password: CharArray): String {
        val json = objectMapper.writeValueAsString(export)
        return encryptJson(json, password)
    }

    fun encryptMigrationData(data: Ts10MigrationData, password: CharArray): String {
        val json = objectMapper.writeValueAsString(data)
        return encryptJson(json, password)
    }

    fun decryptToJson(jweCompact: String, password: CharArray): String {
        val jwe = JWEObject.parse(jweCompact)
        jwe.decrypt(PasswordBasedDecrypter(String(password)))
        return jwe.payload.toString()
    }

    private fun encryptJson(json: String, password: CharArray): String {
        val header = JWEHeader.Builder(JWEAlgorithm.PBES2_HS256_A128KW, EncryptionMethod.A128GCM)
            .contentType("application/json")
            .build()
        val jwe = JWEObject(header, Payload(json))
        jwe.encrypt(PasswordBasedEncrypter(String(password), SALT_BYTES, ITERATION_COUNT))
        return jwe.serialize()
    }

    companion object {
        private const val SALT_BYTES = 8
        private const val ITERATION_COUNT = 120_000
    }
}
