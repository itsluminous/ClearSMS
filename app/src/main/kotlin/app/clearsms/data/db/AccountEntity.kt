package app.clearsms.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.CurrencyCatalog

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
    /**
     * ISO 4217 code of [lastKnownBalance], [creditLimit] and
     * [availableLimit] - the currency of the last transaction or balance
     * statement that touched the account. Added in v23 with the default
     * `INR` (every earlier row was parsed as rupees); never guessed for
     * existing rows.
     */
    @ColumnInfo(defaultValue = CurrencyCatalog.INR_CODE)
    val currency: String = CurrencyCatalog.INR_CODE,
    val lastKnownBalance: Double? = null,
    /** User-configured credit limit; only meaningful for credit cards. */
    val creditLimit: Double? = null,
    /**
     * Available credit limit as last reported by the issuer ("Avl Limit:
     * INR ..."). Deliberately separate from [lastKnownBalance]: a credit
     * card's headroom is not a balance, and conflating the two made cards
     * show a meaningless ₹0. Only meaningful for credit cards.
     */
    val availableLimit: Double? = null,
    val lastUpdated: Long,
)
