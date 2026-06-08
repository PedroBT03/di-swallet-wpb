package di.swallet.wpb.revocation

data class CredentialStatusReference(
    val listUri: String? = null,
    val listIndex: Int? = null,
    val managedByWalletProvider: Boolean = false,
)
