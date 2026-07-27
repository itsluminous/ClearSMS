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
