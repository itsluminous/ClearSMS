package app.clearsms.data.db

import androidx.room.TypeConverter
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType

/** Converts domain enums to/from their names for SQLite storage. */
class Converters {
    @TypeConverter
    fun fromCategory(value: Category): String = value.name

    @TypeConverter
    fun toCategory(value: String): Category = Category.valueOf(value)

    @TypeConverter
    fun fromSubCategory(value: SubCategory?): String? = value?.name

    @TypeConverter
    fun toSubCategory(value: String?): SubCategory? = value?.let(SubCategory::valueOf)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(value: String): AccountType = AccountType.valueOf(value)

    @TypeConverter
    fun fromMerchantCategory(value: MerchantCategory): String = value.name

    @TypeConverter
    fun toMerchantCategory(value: String): MerchantCategory = MerchantCategory.valueOf(value)

    @TypeConverter
    fun fromReminderType(value: ReminderType): String = value.name

    @TypeConverter
    fun toReminderType(value: String): ReminderType = ReminderType.valueOf(value)

    @TypeConverter
    fun fromDeliveryStatus(value: DeliveryStatus?): String? = value?.name

    @TypeConverter
    fun toDeliveryStatus(value: String?): DeliveryStatus? = value?.let(DeliveryStatus::valueOf)

    @TypeConverter
    fun fromMmsStatus(value: MmsStatus?): String? = value?.name

    @TypeConverter
    fun toMmsStatus(value: String?): MmsStatus? = value?.let(MmsStatus::valueOf)
}
