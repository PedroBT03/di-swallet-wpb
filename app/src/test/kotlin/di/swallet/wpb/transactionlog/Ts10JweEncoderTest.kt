package di.swallet.wpb.transactionlog

import com.nimbusds.jose.JWEObject
import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.transactionlog.crypto.Ts10JweEncoder
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionLogExport
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ts10JweEncoderTest {
    private val encoder = Ts10JweEncoder(ObjectMapper())

    @Test
    fun `export round-trip decrypts TransactionLog structure`() {
        val export = Ts10TransactionLogExport(
            transactionLog = listOf(
                Ts10Transaction(
                    transactionIdentifier = "tx-1",
                    time = "2025-07-29T09:11:20",
                    transactionType = Ts10TransactionType.Presentation.name,
                    transactionResult = Ts10TransactionResult.Completed.name,
                ),
            ),
        )
        val password = "export-password".toCharArray()
        val jwe = encoder.encryptTransactionLogExport(export, password)
        assertTrue(jwe.contains("."))

        val json = encoder.decryptToJson(jwe, password)
        assertTrue(json.contains("TransactionLog"))
        assertTrue(json.contains("tx-1"))
    }

    @Test
    fun `export JWE uses PBKDF2 iteration count of at least 120000`() {
        val export = Ts10TransactionLogExport(transactionLog = emptyList())
        val jwe = encoder.encryptTransactionLogExport(export, "export-password".toCharArray())
        val header = JWEObject.parse(jwe).header.toJSONObject()
        assertEquals(120_000, header["p2c"])
    }
}
