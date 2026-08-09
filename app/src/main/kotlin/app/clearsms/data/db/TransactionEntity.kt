package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType

/** A financial transaction extracted from an SMS. */
@Entity(
    tableName = "transactions",
    indices = [
        Index("accountNumber"),
        Index("rawSmsId"),
        Index("timestamp"),
        Index("accountId"),
        Index("referenceNumber"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val type: TransactionType,
    val merchantName: String? = null,
    /** Last 4 digits of the source account or card. */
    val accountNumber: String,
    val bankName: String,
    /**
     * Row id of the owning [AccountEntity], resolved ONCE at ingestion by
     * (canonical bank, last-4). Null when no confident owner exists - a
     * last-4 alone is NOT an identity: the same tail can legitimately
     * exist at several banks, and matching on it cross-contaminated
     * account screens. Read paths key on this id and only fall back to
     * the exact (accountNumber, bankName) pair, never the number alone.
     */
    val accountId: Long? = null,
    /** Transaction time as epoch milliseconds. */
    val timestamp: Long,
    /** Available balance after the transaction, when mentioned in the SMS. */
    val balance: Double? = null,
    val referenceNumber: String? = null,
    val category: MerchantCategory = MerchantCategory.OTHER,
    /** Row id of the originating message in the messages table. */
    val rawSmsId: Long,
    /** Free-form note added by the user. */
    val note: String? = null,
)
