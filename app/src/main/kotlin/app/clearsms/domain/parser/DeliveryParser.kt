package app.clearsms.domain.parser

import app.clearsms.domain.model.ParsedDelivery
import java.time.LocalDate

/**
 * Extracts delivery expectations from courier / e-commerce messages.
 *
 * Only invoked for messages already categorized as deliveries
 * (SubCategory.DELIVERY), which keeps it from becoming a fresh source of
 * false positives. Relative dates are returned unresolved (see
 * [ParsedDelivery.expectedDate]) so the caller can resolve them against the
 * message timestamp.
 */
class DeliveryParser {
    fun parse(
        sender: String,
        body: String,
    ): ParsedDelivery? {
        val explicitDate = findExplicitDate(body)
        val relativeDays =
            when {
                explicitDate != null -> null
                TOMORROW_REGEX.containsMatchIn(body) -> 1L
                TODAY_REGEX.containsMatchIn(body) || OUT_FOR_DELIVERY_REGEX.containsMatchIn(body) -> 0L
                else -> return null
            }
        return ParsedDelivery(
            explicitDate = explicitDate,
            relativeDays = relativeDays,
            merchant = merchantFor(sender, body),
            reference = REFERENCE_REGEX.find(body)?.groupValues?.get(1),
        )
    }

    private fun findExplicitDate(body: String): LocalDate? =
        EXPLICIT_DATE_ANCHORS.firstNotNullOfOrNull { anchor ->
            anchor
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.let(dateParser::parseDate)
        }

    /** Courier / merchant name from the sender id or body, if recognizable. */
    private fun merchantFor(
        sender: String,
        body: String,
    ): String? {
        val upperSender = sender.uppercase()
        MERCHANTS.firstOrNull { (key, _) -> upperSender.contains(key) }?.let { return it.second }
        val upperBody = body.uppercase()
        return MERCHANTS.firstOrNull { (key, _) -> upperBody.contains(key) }?.second
    }

    private companion object {
        /** Reuses the reminder parser's date grammar (DD-MM-YY, DD MMM YYYY, ISO). */
        val dateParser = ReminderParser()

        const val DATE =
            "(?<!\\d)(?:\\d{1,2}[-/](?:\\d{1,2}|[A-Za-z]{3})[-/]\\d{2}(?:\\d{2})?|" +
                "\\d{1,2}[-\\s][A-Za-z]{3,9}[-\\s,]\\s?\\d{2}(?:\\d{2})?)(?!\\d)"

        /** Date-anchored delivery phrases: the keyword must sit next to the date. */
        val EXPLICIT_DATE_ANCHORS =
            listOf(
                "(?:will\\s+be|to\\s+be)\\s+delivered\\s+(?:on|by)\\s+($DATE)",
                "expected\\s+delivery\\s*(?:date)?\\s*(?:is|:|on|by)?\\s*($DATE)",
                "delivery\\s+(?:by|on|date\\s*:?)\\s*($DATE)",
                "arriv(?:ing|es?)\\s+(?:on|by)\\s+($DATE)",
                "scheduled\\s+(?:for|on)\\s+($DATE)",
                "expected\\s+by\\s+($DATE)",
            ).map { Regex("(?i)$it") }

        val TOMORROW_REGEX =
            Regex("(?i)arriv(?:ing|es?)\\s+tomorrow|deliver(?:ed|y)?\\s+(?:by\\s+)?tomorrow|expected\\s+tomorrow")

        val TODAY_REGEX =
            Regex("(?i)arriv(?:ing|es?)\\s+today|deliver(?:ed|y)?\\s+(?:by\\s+)?today|expected\\s+today")

        val OUT_FOR_DELIVERY_REGEX = Regex("(?i)out\\s+for\\s+delivery")

        /** Order / tracking / consignment reference (must contain a digit). */
        val REFERENCE_REGEX =
            Regex(
                "(?i)(?:order|shipment|consignment|tracking|package|parcel|awb)\\s*" +
                    "(?:no\\.?|number|id)?\\s*[:#]?\\s*((?=[A-Za-z0-9-]*\\d)[A-Za-z0-9-]{5,25})",
            )

        val MERCHANTS =
            listOf(
                "AMAZON" to "Amazon",
                "AMZN" to "Amazon",
                "FLIPKART" to "Flipkart",
                "FLPKRT" to "Flipkart",
                "MYNTRA" to "Myntra",
                "MEESHO" to "Meesho",
                "AJIO" to "AJIO",
                "NYKAA" to "Nykaa",
                "DELHIVERY" to "Delhivery",
                "DLHVRY" to "Delhivery",
                "BLUEDART" to "Blue Dart",
                "BLUDRT" to "Blue Dart",
                "EKART" to "Ekart",
                "DTDC" to "DTDC",
                "XPRESSBEES" to "XpressBees",
                "XPRSBS" to "XpressBees",
                "SHIPROCKET" to "Shiprocket",
                "INDPST" to "India Post",
                "INDIA POST" to "India Post",
                "FEDEX" to "FedEx",
                "DHL" to "DHL",
                "ECOMEX" to "Ecom Express",
                "SWIGGY" to "Swiggy",
                "ZOMATO" to "Zomato",
                "BIGBASKET" to "bigbasket",
                "BLINKIT" to "Blinkit",
            )
    }
}
