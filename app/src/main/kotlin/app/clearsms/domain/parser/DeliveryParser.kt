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

    /**
     * Courier / merchant name from the sender id or body, if recognizable.
     *
     * The BODY matters as much as the sender: courier deliveries routinely
     * arrive from someone else's sender id (a bank announcing "your Debit
     * Card will be delivered via Blue Dart" from HDFCBK, a food chain via a
     * generic shortcode) with the courier named only in the text. In those
     * cases the courier is shown as the DELIVERY AGENT on the Alerts card —
     * the message itself stays attributed to its real sender (the bank), and
     * nothing here reclassifies the message: this parser only ever runs on
     * messages already categorized as deliveries.
     */
    private fun merchantFor(
        sender: String,
        body: String,
    ): String? {
        val upperSender = sender.uppercase()
        MERCHANTS.firstOrNull { (key, _) -> upperSender.contains(key) }?.let { return it.second }
        // URLs are stripped BEFORE substring matching so a tracking link can
        // never misattribute the delivery to an unrelated brand whose name
        // happens to appear in a path or query string. Brand DOMAINS are then
        // matched separately, against the parsed hostname only.
        val urls = URL_REGEX.findAll(body).toList()
        val bodySansUrls = URL_REGEX.replace(body, " ").uppercase()
        MERCHANTS.firstOrNull { (key, _) -> bodySansUrls.contains(key) }?.let { return it.second }
        return urls.firstNotNullOfOrNull { url -> brandForHost(url.groupValues[1].lowercase()) }
    }

    /**
     * Brand for a URL hostname: exact registered-domain match or a true
     * subdomain of it ("www.croma.com" -> Croma). A domain merely EMBEDDED
     * elsewhere in a hostname ("croma.com.evil.net") never matches.
     */
    private fun brandForHost(host: String): String? =
        BRAND_DOMAINS.firstNotNullOfOrNull { (domain, name) ->
            name.takeIf { host == domain || host.endsWith(".$domain") }
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

        /**
         * Order / tracking / consignment reference (must contain a digit).
         * Covers "Article No:JQ0XXX9386XIN" (India Post), "Awb #338XXX641",
         * and underscore-bearing order ids like "Order OPD_NHK-130".
         */
        val REFERENCE_REGEX =
            Regex(
                "(?i)(?:order|shipment|consignment|tracking|package|parcel|awb|article)\\s*" +
                    "(?:no\\.?|number|id)?\\s*[:#]?\\s*((?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{5,25})",
            )

        /**
         * Courier / merchant lookup keys, matched against BOTH the sender id
         * and the body text (uppercased, substring match) — several couriers
         * only ever appear in the body ("via Blue Dart", "? INDIAPOST",
         * "Nimbuspost Courier"). "DOMINO" covers both the "Domino's" and
         * "DOMINOS" spellings.
         */
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
                "BLUE DART" to "Blue Dart",
                "BLUDRT" to "Blue Dart",
                "EKART" to "Ekart",
                "DTDC" to "DTDC",
                "XPRESSBEES" to "XpressBees",
                "XPRSBS" to "XpressBees",
                "SHIPROCKET" to "Shiprocket",
                "INDPST" to "India Post",
                "INDPOST" to "India Post",
                "INDIA POST" to "India Post",
                "INDIAPOST" to "India Post",
                "FEDEX" to "FedEx",
                "DHL" to "DHL",
                "ECOMEX" to "Ecom Express",
                "SWIGGY" to "Swiggy",
                "ZOMATO" to "Zomato",
                "BIGBASKET" to "bigbasket",
                "BLINKIT" to "Blinkit",
                "DOMINO" to "Domino's",
                "NIMBUSPOST" to "Nimbuspost",
                "SHADOWFAX" to "Shadowfax",
                // Body signature is "Rgds, Team Croma"; the sender id varies.
                "CROMA" to "Croma",
            )

        /** URL with its hostname captured; spans are excluded from name matching. */
        val URL_REGEX = Regex("(?i)\\bhttps?://([A-Za-z0-9.-]{1,80})(?:[/?#]\\S{0,200})?")

        /**
         * Known brand registered domains, matched against URL hostnames only
         * (see [brandForHost]). Brands like Croma often identify themselves
         * mainly through their order-tracking link.
         */
        val BRAND_DOMAINS =
            listOf(
                "croma.com" to "Croma",
                "amazon.in" to "Amazon",
                "flipkart.com" to "Flipkart",
                "myntra.com" to "Myntra",
                "delhivery.com" to "Delhivery",
                "bluedart.com" to "Blue Dart",
                "dtdc.in" to "DTDC",
                "indiapost.gov.in" to "India Post",
                "ekartlogistics.com" to "Ekart",
            )
    }
}
