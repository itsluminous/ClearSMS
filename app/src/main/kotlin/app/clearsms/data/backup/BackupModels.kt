package app.clearsms.data.backup

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import kotlinx.serialization.Serializable

/** Root document of a local backup file. */
@Serializable
data class BackupDocument(
    val formatVersion: Int = 1,
    val createdAt: Long,
    val messages: List<MessageBackup> = emptyList(),
    val accounts: List<AccountBackup> = emptyList(),
    val transactions: List<TransactionBackup> = emptyList(),
    val rules: List<RuleBackup> = emptyList(),
    val reminders: List<ReminderBackup> = emptyList(),
)

@Serializable
data class MessageBackup(
    val id: Long,
    val threadId: Long,
    val sender: String,
    val normalizedSender: String,
    val body: String,
    val timestamp: Long,
    val isRead: Boolean,
    val isArchived: Boolean,
    val category: String,
    val subCategory: String? = null,
    val extractedOtp: String? = null,
    val extractedDataJson: String? = null,
    val isBlockedSender: Boolean = false,
)

@Serializable
data class AccountBackup(
    val id: Long,
    val accountNumber: String,
    val bankName: String,
    val type: String,
    val lastKnownBalance: Double? = null,
    val creditLimit: Double? = null,
    val lastUpdated: Long,
)

@Serializable
data class TransactionBackup(
    val id: Long,
    val amount: Double,
    val type: String,
    val merchantName: String? = null,
    val accountNumber: String,
    val bankName: String,
    val timestamp: Long,
    val balance: Double? = null,
    val referenceNumber: String? = null,
    val category: String,
    val rawSmsId: Long,
    val note: String? = null,
)

@Serializable
data class RuleBackup(
    val id: String,
    val name: String,
    val priority: Int,
    val matchJson: String,
    val actionJson: String,
    val isUserDefined: Boolean,
    val source: String,
    val createdAt: Long,
)

@Serializable
data class ReminderBackup(
    val id: Long,
    val type: String,
    val dueDate: Long? = null,
    val totalDue: Double? = null,
    val minDue: Double? = null,
    val accountLast4: String? = null,
    val bankName: String? = null,
    val rawSmsId: Long,
    val createdAt: Long,
)

internal fun MessageEntity.toBackup() =
    MessageBackup(
        id,
        threadId,
        sender,
        normalizedSender,
        body,
        timestamp,
        isRead,
        isArchived,
        category.name,
        subCategory?.name,
        extractedOtp,
        extractedDataJson,
        isBlockedSender,
    )

internal fun MessageBackup.toEntity() =
    MessageEntity(
        id = id,
        threadId = threadId,
        sender = sender,
        normalizedSender = normalizedSender,
        body = body,
        timestamp = timestamp,
        isRead = isRead,
        isArchived = isArchived,
        category = Category.valueOf(category),
        subCategory = subCategory?.let(SubCategory::valueOf),
        extractedOtp = extractedOtp,
        extractedDataJson = extractedDataJson,
        isBlockedSender = isBlockedSender,
    )

internal fun AccountEntity.toBackup() = AccountBackup(id, accountNumber, bankName, type.name, lastKnownBalance, creditLimit, lastUpdated)

internal fun AccountBackup.toEntity() =
    AccountEntity(
        id = id,
        accountNumber = accountNumber,
        bankName = bankName,
        type = AccountType.valueOf(type),
        lastKnownBalance = lastKnownBalance,
        creditLimit = creditLimit,
        lastUpdated = lastUpdated,
    )

internal fun TransactionEntity.toBackup() =
    TransactionBackup(
        id,
        amount,
        type.name,
        merchantName,
        accountNumber,
        bankName,
        timestamp,
        balance,
        referenceNumber,
        category.name,
        rawSmsId,
        note,
    )

internal fun TransactionBackup.toEntity() =
    TransactionEntity(
        id = id,
        amount = amount,
        type = TransactionType.valueOf(type),
        merchantName = merchantName,
        accountNumber = accountNumber,
        bankName = bankName,
        timestamp = timestamp,
        balance = balance,
        referenceNumber = referenceNumber,
        category = MerchantCategory.valueOf(category),
        rawSmsId = rawSmsId,
        note = note,
    )

internal fun RuleEntity.toBackup() = RuleBackup(id, name, priority, matchJson, actionJson, isUserDefined, source, createdAt)

internal fun RuleBackup.toEntity() =
    RuleEntity(
        id = id,
        name = name,
        priority = priority,
        matchJson = matchJson,
        actionJson = actionJson,
        isUserDefined = isUserDefined,
        source = source,
        createdAt = createdAt,
    )

internal fun ReminderEntity.toBackup() =
    ReminderBackup(id, type.name, dueDate, totalDue, minDue, accountLast4, bankName, rawSmsId, createdAt)

internal fun ReminderBackup.toEntity() =
    ReminderEntity(
        id = id,
        type = ReminderType.valueOf(type),
        dueDate = dueDate,
        totalDue = totalDue,
        minDue = minDue,
        accountLast4 = accountLast4,
        bankName = bankName,
        rawSmsId = rawSmsId,
        createdAt = createdAt,
    )
