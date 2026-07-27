package app.clearsms.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.NotificationAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Notification for a parsed bank transaction: the essentials up front
 * (signed amount, merchant, account, bank) instead of the raw SMS.
 *
 * Semantics mirror the Finance UI's three-way split — debit (red, "− ₹…"),
 * credit (green, "+ ₹…") and balance-only update (blue, unsigned "₹…"),
 * using the same fixed colors as
 * [app.clearsms.ui.theme.SemanticAmountColors] via `R.color.amount_*`
 * (day/night variants), deliberately independent of the Material dynamic
 * palette.
 *
 * The standard notification template cannot color its title —
 * [NotificationCompat.Builder.setColor] only tints the small icon/accent —
 * so on API 24+ the amount is rendered through a custom content view
 * ([NotificationCompat.Builder.setCustomContentView] +
 * [NotificationCompat.DecoratedCustomViewStyle]) whose amount TextView gets
 * an explicit color; the expanded custom view carries the full original SMS
 * body. On API 23 (`minSdk`), where the decorated style does not exist, the
 * template title is a [SpannableString] with a [ForegroundColorSpan] — the
 * platform templates on API 21–23 preserve title spans, so the amount stays
 * colored there too. Either way the text is never shipped uncolored.
 *
 * Tapping deep-links to the conversation at THAT message:
 * `clearsms://conversation/{threadId}?messageId={messageId}` — the query
 * parameter is named `messageId` and is also present as the "message_id"
 * intent extra ([MessageActionReceiver.EXTRA_MESSAGE_ID]) for the UI to
 * highlight the target message.
 *
 * Notifications are grouped under one shade group so a burst of bank
 * messages collapses instead of spamming, on a dedicated DEFAULT-importance
 * "transactions" channel (deliberately not heads-up).
 */
@Singleton
class TransactionNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) {
        /**
         * Posts a parsed-transaction notification for [message].
         *
         * @return false when the message's extracted data does not contain
         *   enough to render (no amount+type and no balance) — the caller
         *   falls back to the plain message notification.
         */
        fun notify(
            message: MessageEntity,
            selected: Set<NotificationAction>,
        ): Boolean {
            val notification = buildNotification(message, selected) ?: return false
            try {
                val manager = NotificationManagerCompat.from(context)
                manager.notify(notificationId(message.id), notification)
                manager.notify(GROUP_SUMMARY_ID, groupSummary())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
            return true
        }

        /**
         * Builds the notification, or null when there is nothing to render.
         * Separated from [notify] so tests can inspect the result.
         */
        internal fun buildNotification(
            message: MessageEntity,
            selected: Set<NotificationAction>,
        ): Notification? {
            val details = decodeDetails(message.extractedDataJson) ?: return null
            val content =
                buildContent(
                    details = details,
                    balanceUpdateLabel = context.getString(R.string.transaction_balance_update),
                    accountFormat = context.getString(R.string.transaction_account_short),
                ) ?: return null
            Channels.ensureCreated(context)

            val notificationId = notificationId(message.id)
            val planned =
                NotificationActionPlanner.forMessage(
                    selected,
                    repliable = NotificationActionPlanner.isRepliableAddress(message.sender),
                )
            val amountColor = ContextCompat.getColor(context, amountColorRes(content.kind))
            val builder =
                NotificationCompat
                    .Builder(context, Channels.TRANSACTIONS)
                    .setSmallIcon(R.drawable.ic_notification)
                    // Title/text stay set for accessibility services and
                    // surfaces that ignore custom views (e.g. wearables).
                    .setContentTitle(content.title)
                    .setContentText(content.text)
                    .setColor(amountColor)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setGroup(GROUP_KEY)
                    .setContentIntent(contentIntent(message))
                    .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(contentView(content, R.layout.notification_transaction, amountColor))
                    .setCustomBigContentView(
                        contentView(content, R.layout.notification_transaction_big, amountColor)
                            .apply { setTextViewText(R.id.transaction_body, message.body) },
                    )
            } else {
                // API 23 fallback: color the template title via a span
                // (preserved by the platform templates on 21–23) and keep
                // the full SMS on expand through BigTextStyle.
                val coloredTitle =
                    SpannableString(content.title).apply {
                        setSpan(ForegroundColorSpan(amountColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                builder
                    .setContentTitle(coloredTitle)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            }
            MessageActionFactory.build(context, message, notificationId, planned).forEach(builder::addAction)
            return builder.build()
        }

        fun cancel(messageId: Long) {
            NotificationManagerCompat.from(context).cancel(notificationId(messageId))
        }

        /** Inflates one of the custom layouts with the amount (explicitly colored) and detail line. */
        private fun contentView(
            content: Content,
            @LayoutRes layout: Int,
            amountColor: Int,
        ): RemoteViews =
            RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.transaction_amount, content.title)
                setTextColor(R.id.transaction_amount, amountColor)
                setTextViewText(R.id.transaction_details, content.text)
            }

        /** Deep link carrying the target message id (query param `messageId` + extra). */
        private fun contentIntent(message: MessageEntity): PendingIntent {
            val uri = "clearsms://conversation/${message.threadId}?messageId=${message.id}".toUri()
            val intent =
                Intent(Intent.ACTION_VIEW, uri)
                    .setClassName(context, "app.clearsms.MainActivity")
                    .putExtra(MessageNotifier.EXTRA_THREAD_ID, message.threadId)
                    .putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, message.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                notificationId(message.id),
                intent,
                NotificationIntents.flags(),
            )
        }

        private fun groupSummary(): Notification =
            NotificationCompat
                .Builder(context, Channels.TRANSACTIONS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.channel_transactions))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()

        private fun decodeDetails(raw: String?): Map<String, String>? =
            raw?.let {
                try {
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
                } catch (_: Exception) {
                    null
                }
            }

        private fun notificationId(messageId: Long) = TRANSACTION_NOTIFICATION_ID_BASE + (messageId % 1000).toInt()

        /** What a transaction notification shows; pure and unit-testable. */
        data class Content(
            val kind: Kind,
            val title: String,
            val text: String,
        ) {
            enum class Kind { DEBIT, CREDIT, BALANCE }
        }

        companion object {
            private const val TRANSACTION_NOTIFICATION_ID_BASE = 30_000
            private const val GROUP_SUMMARY_ID = 31_000
            private const val GROUP_KEY = "app.clearsms.TRANSACTIONS"

            /**
             * Fixed day/night color resource for a transaction kind — the
             * notification twin of [app.clearsms.ui.theme.SemanticAmountColors].
             */
            @ColorRes
            fun amountColorRes(kind: Content.Kind): Int =
                when (kind) {
                    Content.Kind.DEBIT -> R.color.amount_debit
                    Content.Kind.CREDIT -> R.color.amount_credit
                    Content.Kind.BALANCE -> R.color.amount_balance
                }

            /**
             * Builds notification content from the message's extracted
             * detail map (keys written by the ingestion pipeline: "amount",
             * "type", "merchant", "account_last4", "bank", "balance").
             *
             * Kind selection mirrors the UI: an amount with type debit /
             * credit wins; otherwise a lone balance renders as a balance-only
             * update; with neither there is nothing to show (null).
             */
            fun buildContent(
                details: Map<String, String>,
                balanceUpdateLabel: String,
                accountFormat: String,
            ): Content? {
                val amount = details["amount"]?.replace(",", "")?.toDoubleOrNull()
                val type = details["type"]?.lowercase()
                val balance = details["balance"]?.replace(",", "")?.toDoubleOrNull()

                val kind =
                    when {
                        amount != null && type == "debit" -> Content.Kind.DEBIT
                        amount != null && type == "credit" -> Content.Kind.CREDIT
                        balance != null -> Content.Kind.BALANCE
                        else -> return null
                    }
                val title =
                    when (kind) {
                        Content.Kind.DEBIT -> "− ₹${grouped(amount!!)}"
                        Content.Kind.CREDIT -> "+ ₹${grouped(amount!!)}"
                        Content.Kind.BALANCE -> "₹${grouped(balance!!)}"
                    }
                return Content(
                    kind = kind,
                    title = title,
                    text =
                        compactText(
                            merchant = details["merchant"],
                            accountLast4 = details["account_last4"],
                            bank = details["bank"],
                            balanceOnly = kind == Content.Kind.BALANCE,
                            balanceUpdateLabel = balanceUpdateLabel,
                            accountFormat = accountFormat,
                        ),
                )
            }

            /**
             * One-liner like "Swiggy · A/c 2863 · HDFC Bank". Missing fields
             * drop out; a balance-only update with no merchant leads with
             * [balanceUpdateLabel].
             */
            fun compactText(
                merchant: String?,
                accountLast4: String?,
                bank: String?,
                balanceOnly: Boolean,
                balanceUpdateLabel: String,
                accountFormat: String,
            ): String =
                listOfNotNull(
                    merchant ?: balanceUpdateLabel.takeIf { balanceOnly },
                    accountLast4?.let { String.format(accountFormat, it) },
                    bank,
                ).joinToString(" · ")

            /**
             * Indian digit grouping ("1,299", "12,430", "1,00,000"), two
             * decimals only when the amount has a fraction. Local copy of the
             * UI's format so the platform layer does not depend on ui code.
             */
            internal fun grouped(value: Double): String {
                val rounded = BigDecimal(abs(value)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
                val plain = rounded.toPlainString()
                val integerPart = plain.substringBefore('.')
                val fractionPart = plain.substringAfter('.', missingDelimiterValue = "")

                val groupedInt = StringBuilder()
                val head = if (integerPart.length > 3) integerPart.dropLast(3) else ""
                val tail = integerPart.takeLast(3)
                if (head.isNotEmpty()) {
                    val pairs = ArrayDeque<String>()
                    var index = head.length
                    while (index > 0) {
                        val start = maxOf(0, index - 2)
                        pairs.addFirst(head.substring(start, index))
                        index = start
                    }
                    groupedInt.append(pairs.joinToString(","))
                    groupedInt.append(',')
                }
                groupedInt.append(tail)
                return if (fractionPart.isEmpty()) groupedInt.toString() else "$groupedInt.$fractionPart"
            }
        }
    }
