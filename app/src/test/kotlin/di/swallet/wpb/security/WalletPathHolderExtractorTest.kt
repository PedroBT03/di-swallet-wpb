package di.swallet.wpb.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WalletPathHolderExtractorTest {
    @Test
    fun `extracts holder from wallet key path`() {
        assertEquals(
            "holder-1",
            WalletPathHolderExtractor.extractHolderId("/api/v1/wallet/keys/holder-1", "POST"),
        )
    }

    @Test
    fun `extracts holder from credential list path only on GET`() {
        assertEquals(
            "holder-1",
            WalletPathHolderExtractor.extractHolderId("/api/v1/wallet/credentials/holder-1", "GET"),
        )
        assertNull(
            WalletPathHolderExtractor.extractHolderId("/api/v1/wallet/credentials/42", "GET"),
        )
        assertNull(
            WalletPathHolderExtractor.extractHolderId("/api/v1/wallet/credentials/42/presentation", "POST"),
        )
    }
}
