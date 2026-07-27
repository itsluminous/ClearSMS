package app.clearsms.data.backup

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.rules.RuleSources
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
    val formatVersion: Int = FORMAT_VERSION,
    val createdAt: Long,
    val messages: List<MessageBackup> = emptyList(),
    val accounts: List<AccountBackup> = emptyList(),
    val transactions: List<TransactionBackup> = emptyList(),
    val rules: List<RuleBackup> = emptyList(),
    val reminders: List<ReminderBackup> = emptyList(),
) {
    companion object {
        /**
         * Current backup document format. Bump when the document shape
         * changes incompatibly; restore rejects documents newer than this
         * and migrates (or defaults) older ones in [BackupManager.importFrom].
         */
        const val FORMAT_VERSION = 1
    }
}

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

/**
 * Tally of adjustments made while mapping a backup document to entities.
 * Restore never throws on unknown enum values (a hand-edited or future
 * backup must not brick the restore); it defaults or skips and reports here.
 */
class RestoreIssues {
    /** Fields whose stored value was unknown and replaced with a safe default. */
    var defaultedValues: Int = 0
        internal set

    /** Rows dropped entirely because no safe default existed. */
    var skippedRows: Int = 0
        internal set
}

/** Lenient enum lookup: unknown values fall back to [default] and are counted. */
internal inline fun <reified T : Enum<T>> RestoreIssues.enumOrDefault(
    value: String,
    default: T,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: default.also { defaultedValues++ }

/** Lenient nullable enum lookup: unknown non-null values become null and are counted. */
internal inline fun <reified T : Enum<T>> RestoreIssues.enumOrNull(value: String?): T? {
    if (value == null) return null
    val resolved = enumValues<T>().firstOrNull { it.name == value }
    if (resolved == null) defaultedValues++
    return resolved
}

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

internal fun MessageBackup.toEntity(issues: RestoreIssues) =
    MessageEntity(
        id = id,
        threadId = threadId,
        sender = sender,
        normalizedSender = normalizedSender,
        body = body,
        timestamp = timestamp,
        isRead = isRead,
        isArchived = isArchived,
        category = issues.enumOrDefault(category, Category.UNKNOWN),
        subCategory = issues.enumOrNull<SubCategory>(subCategory),
        extractedOtp = extractedOtp,
        extractedDataJson = extractedDataJson,
        isBlockedSender = isBlockedSender,
    )

internal fun AccountEntity.toBackup() = AccountBackup(id, accountNumber, bankName, type.name, lastKnownBalance, creditLimit, lastUpdated)

internal fun AccountBackup.toEntity(issues: RestoreIssues) =
    AccountEntity(
        id = id,
        accountNumber = accountNumber,
        bankName = bankName,
        type = issues.enumOrDefault(type, AccountType.SAVINGS),
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

/**
 * Maps a transaction row, or returns null (counted as skipped) when the
 * debit/credit type is unknown — guessing a direction would corrupt the
 * finance dashboard, so the row is dropped instead.
 */
internal fun TransactionBackup.toEntityOrNull(issues: RestoreIssues): TransactionEntity? {
    val resolvedType =
        TransactionType.entries.firstOrNull { it.name == type }
            ?: run {
                issues.skippedRows++
                return null
            }
    return TransactionEntity(
        id = id,
        amount = amount,
        type = resolvedType,
        merchantName = merchantName,
        accountNumber = accountNumber,
        bankName = bankName,
        timestamp = timestamp,
        balance = balance,
        referenceNumber = referenceNumber,
        category = issues.enumOrDefault(category, MerchantCategory.OTHER),
        rawSmsId = rawSmsId,
        note = note,
    )
}

internal fun RuleEntity.toBackup() = RuleBackup(id, name, priority, matchJson, actionJson, isUserDefined, source, createdAt)

/**
 * Maps a restored rule as a USER rule, regardless of what the file claims.
 *
 * `source` and `isUserDefined` from the file are never trusted: a crafted
 * backup could otherwise inject rows masquerading as bundled community rules.
 * The id is namespaced into the user id space so it can never collide with
 * (and overwrite) a builtin rule's row.
 */
internal fun RuleBackup.toUserEntity() =
    RuleEntity(
        id = RuleEntity.namespacedUserId(id),
        name = name,
        priority = priority,
        matchJson = matchJson,
        actionJson = actionJson,
        isUserDefined = true,
        source = RuleSources.USER,
        createdAt = createdAt,
    )

internal fun ReminderEntity.toBackup() =
    ReminderBackup(id, type.name, dueDate, totalDue, minDue, accountLast4, bankName, rawSmsId, createdAt)

internal fun ReminderBackup.toEntity(issues: RestoreIssues) =
    ReminderEntity(
        id = id,
        type = issues.enumOrDefault(type, ReminderType.OTHER),
        dueDate = dueDate,
        totalDue = totalDue,
        minDue = minDue,
        accountLast4 = accountLast4,
        bankName = bankName,
        rawSmsId = rawSmsId,
        createdAt = createdAt,
    )
