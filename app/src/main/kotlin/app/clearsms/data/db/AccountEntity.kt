package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.AccountType

/** A bank account, credit card or wallet detected from transaction SMS. */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["accountNumber", "bankName"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Last 4 digits of the account or card number. */
    val accountNumber: String,
    val bankName: String,
    val type: AccountType,
    val lastKnownBalance: Double? = null,
    /** User-configured credit limit; only meaningful for credit cards. */
    val creditLimit: Double? = null,
    val lastUpdated: Long,
)
