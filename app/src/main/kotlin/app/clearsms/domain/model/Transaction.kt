package app.clearsms.domain.model

import java.time.LocalDateTime

/** A financial transaction extracted from an SMS. */
data class Transaction(
    val id: Long,
    val amount: Double,
    val type: TransactionType,
    val merchantName: String?,
    val accountNumber: String,
    val bankName: String,
    val dateTime: LocalDateTime,
    val balance: Double?,
    val referenceNumber: String?,
    val category: MerchantCategory,
    val rawSmsId: Long,
)
