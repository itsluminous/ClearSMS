package app.clearsms.notification

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.notification.TransactionNotifier.Content
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The standard template cannot color its title text (setColor only tints the
 * small icon/accent), so the amount MUST travel through the custom content
 * views with an explicit text color. These tests pin that wiring: custom
 * collapsed + expanded layouts are attached and the accent carries the
 * resolved fixed `amount_*` color for the transaction kind.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionNotifierCustomViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier =
        TransactionNotifier(
            context,
            Json,
            object : NotificationSenderResolver(
                context,
                app.clearsms.sms.ContactsSource(context),
                app.clearsms.data.senderid
                    .SenderIdStore(context),
            ) {
                override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
            },
            SenderIconFactory(context),
        )

    private fun message(extracted: String) =
        MessageEntity(
            id = 42L,
            threadId = 3L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "Rs.1,299.00 debited from a/c **2863 to Swiggy. Avl bal Rs.12,430.00",
            timestamp = 1_000L,
            category = Category.IMPORTANT,
            extractedDataJson = extracted,
        )

    @Test
    fun `debit notification uses the custom collapsed and expanded layouts`() {
        val notification =
            notifier.buildNotification(
                message("""{"amount":"1299.0","type":"debit","merchant":"Swiggy"}"""),
                MessageNotifier.DEFAULT_SELECTED,
            )
        assertThat(notification).isNotNull()
        @Suppress("DEPRECATION")
        assertThat(notification!!.contentView.layoutId).isEqualTo(R.layout.notification_transaction)
        @Suppress("DEPRECATION")
        assertThat(notification.bigContentView.layoutId).isEqualTo(R.layout.notification_transaction_big)
    }

    @Test
    fun `accent carries the resolved fixed debit color`() {
        val notification =
            notifier.buildNotification(
                message("""{"amount":"1299.0","type":"debit"}"""),
                MessageNotifier.DEFAULT_SELECTED,
            )
        assertThat(notification!!.color)
            .isEqualTo(ContextCompat.getColor(context, R.color.amount_debit))
    }

    @Test
    fun `credit and balance notifications resolve their own fixed colors`() {
        val credit =
            notifier.buildNotification(
                message("""{"amount":"5000.0","type":"credit"}"""),
                MessageNotifier.DEFAULT_SELECTED,
            )
        assertThat(credit!!.color).isEqualTo(ContextCompat.getColor(context, R.color.amount_credit))

        val balance =
            notifier.buildNotification(
                message("""{"balance":"12430.0","bank":"HDFC Bank"}"""),
                MessageNotifier.DEFAULT_SELECTED,
            )
        assertThat(balance!!.color).isEqualTo(ContextCompat.getColor(context, R.color.amount_balance))
    }

    @Test
    fun `kind to color-resource mapping is exhaustive and distinct`() {
        val mapped = Content.Kind.entries.map(TransactionNotifier::amountColorRes)
        assertThat(mapped)
            .containsExactly(R.color.amount_debit, R.color.amount_credit, R.color.amount_balance)
            .inOrder()
    }

    @Test
    fun `unparseable extraction yields no notification`() {
        assertThat(notifier.buildNotification(message("not json"), MessageNotifier.DEFAULT_SELECTED)).isNull()
        assertThat(
            notifier.buildNotification(
                message("""{"merchant":"Swiggy"}"""),
                MessageNotifier.DEFAULT_SELECTED,
            ),
        ).isNull()
    }
}
