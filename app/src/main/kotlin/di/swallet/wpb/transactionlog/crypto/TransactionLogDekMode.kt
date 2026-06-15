package di.swallet.wpb.transactionlog.crypto

enum class TransactionLogDekMode {
    SERVER,
    HOLDER,
    ;

    companion object {
        fun fromConfig(value: String): TransactionLogDekMode =
            when (value.trim().lowercase()) {
                "holder" -> HOLDER
                else -> SERVER
            }
    }
}
