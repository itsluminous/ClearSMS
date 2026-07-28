package app.clearsms.data.repository

import app.clearsms.data.db.TransactionEntity
import kotlin.math.abs

/**
 * Collapses duplicate transaction rows born from repeated bank SMS for the
 * SAME payment (a spend alert plus a later statement / "payment received"
 * line), which inflated month totals. Two tiers, both deliberately
 * conservative — merging two genuinely distinct payments is worse than
 * leaving a duplicate:
 *
 * 1. **Reference match.** Same normalized reference (UTR/RRN/txn id) + same
 *    amount, type and account last-4, with compatible issuers (equal, or one
 *    blank where the alert never named the bank). References are globally
 *    unique per transaction, so the match holds at ANY time distance — a
 *    charge and its statement line days later are still one payment. A
 *    reference only counts when it looks like one: at least
 *    [MIN_REFERENCE_LENGTH] characters AND at least one digit. (Observed
 *    on a real inbox: word fragments like "details"/"erence" captured as
 *    references would otherwise chain unrelated payments together.)
 * 2. **Near-duplicate alert.** With no reference to lean on, two rows are
 *    the same payment only when amount, type and the exact
 *    (last-4, bank) account all match within [NEAR_DUPLICATE_WINDOW_MS] —
 *    and nothing contradicts it. Window evidence from a real corpus:
 *    duplicate alerts for one payment overwhelmingly arrive within a
 *    minute of each other, while verified DISTINCT same-amount pairs with
 *    no distinguishing balance/merchant were never closer than ~125s
 *    (two identical card spends at one merchant). 90s keeps the duplicate
 *    mass with margin on both sides. Contradictions that veto the merge
 *    even inside the window, each observed in real distinct pairs seconds
 *    apart: differing post-transaction balances (the money moved twice),
 *    differing merchants (same-amount SIPs into different funds), differing
 *    valid references, or rows already linked to different accounts.
 *
 * Never merged across accounts or types, and never across two different
 * NAMED banks — the same last-4 legitimately exists at several banks.
 */
object TransactionDeduplication {
    /** Shortest token accepted as a transaction reference. */
    const val MIN_REFERENCE_LENGTH = 6

    /** Tier-2 pairing window; see the class doc for the evidence behind 90s. */
    const val NEAR_DUPLICATE_WINDOW_MS = 90_000L

    /**
     * Canonical form of a reference: trimmed and uppercased. Returns null
     * for tokens that cannot identify a transaction — too short, or with no
     * digit at all (extraction noise like "details" / "Number").
     */
    fun normalizedReference(reference: String?): String? {
        val trimmed = reference?.trim()?.uppercase() ?: return null
        if (trimmed.length < MIN_REFERENCE_LENGTH) return null
        if (trimmed.none { it.isDigit() }) return null
        return trimmed
    }

    /** True when [a] and [b] describe the same payment under either tier. */
    fun isDuplicate(
        a: TransactionEntity,
        b: TransactionEntity,
    ): Boolean = isReferenceDuplicate(a, b) || isNearDuplicateAlert(a, b)

    /** Tier 1: same normalized reference on the same account, any time gap. */
    fun isReferenceDuplicate(
        a: TransactionEntity,
        b: TransactionEntity,
    ): Boolean {
        val refA = normalizedReference(a.referenceNumber) ?: return false
        val refB = normalizedReference(b.referenceNumber) ?: return false
        if (refA != refB) return false
        if (a.amount != b.amount || a.type != b.type) return false
        if (a.accountNumber != b.accountNumber) return false
        if (linkedToDifferentAccounts(a, b)) return false
        return banksCompatible(a.bankName, b.bankName)
    }

    /** Tier 2: reference-less twin alerts moments apart, guards permitting. */
    fun isNearDuplicateAlert(
        a: TransactionEntity,
        b: TransactionEntity,
    ): Boolean {
        val refA = normalizedReference(a.referenceNumber)
        val refB = normalizedReference(b.referenceNumber)
        // Two valid references either agree (tier 1) or prove two payments.
        if (refA != null && refB != null) return false
        if (a.amount != b.amount || a.type != b.type) return false
        if (a.accountNumber != b.accountNumber || a.bankName != b.bankName) return false
        if (linkedToDifferentAccounts(a, b)) return false
        if (abs(a.timestamp - b.timestamp) > NEAR_DUPLICATE_WINDOW_MS) return false
        // Differing post-transaction balances = the money moved twice.
        if (a.balance != null && b.balance != null && a.balance != b.balance) return false
        // Differing merchants = two purchases (same-amount SIPs, split pays).
        val merchantA = a.merchantName?.trim().orEmpty()
        val merchantB = b.merchantName?.trim().orEmpty()
        if (merchantA.isNotEmpty() && merchantB.isNotEmpty() && !merchantA.equals(merchantB, ignoreCase = true)) return false
        return true
    }

    /**
     * One row carrying both alerts' knowledge. Keeps the identity (id,
     * rawSmsId, timestamp) of the RICHER row — so the row always points at a
     * real source message — fills its gaps from the other, and preserves
     * user notes from BOTH rows.
     */
    fun collapse(
        a: TransactionEntity,
        b: TransactionEntity,
    ): TransactionEntity {
        val keep = if (richness(b) > richness(a)) b else a
        val drop = if (keep === a) b else a
        return keep.copy(
            merchantName = keep.merchantName?.takeIf { it.isNotBlank() } ?: drop.merchantName,
            bankName = keep.bankName.ifEmpty { drop.bankName },
            accountId = keep.accountId ?: drop.accountId,
            balance = keep.balance ?: drop.balance,
            referenceNumber =
                keep.referenceNumber?.takeIf { normalizedReference(it) != null }
                    ?: drop.referenceNumber?.takeIf { normalizedReference(it) != null }
                    ?: keep.referenceNumber
                    ?: drop.referenceNumber,
            note = mergeNotes(keep.note, drop.note),
        )
    }

    /** Field wealth used to pick the surviving row of a duplicate pair. */
    private fun richness(t: TransactionEntity): Int {
        var score = 0
        if (normalizedReference(t.referenceNumber) != null) score += 8
        if (!t.merchantName.isNullOrBlank()) score += 4
        if (t.bankName.isNotEmpty()) score += 2
        if (t.balance != null) score += 1
        return score
    }

    /** User notes are never dropped: keep one, or join two distinct ones. */
    fun mergeNotes(
        a: String?,
        b: String?,
    ): String? =
        when {
            a.isNullOrBlank() -> b?.takeIf { it.isNotBlank() }
            b.isNullOrBlank() || a == b -> a
            else -> "$a\n$b"
        }

    private fun banksCompatible(
        a: String,
        b: String,
    ): Boolean = a == b || a.isEmpty() || b.isEmpty()

    private fun linkedToDifferentAccounts(
        a: TransactionEntity,
        b: TransactionEntity,
    ): Boolean = a.accountId != null && b.accountId != null && a.accountId != b.accountId

    /** Outcome of a batch [dedupe] pass over existing rows. */
    data class DedupeResult(
        /** Rows whose fields changed and must be UPDATEd (survivors). */
        val updated: List<TransactionEntity>,
        /** Row ids to DELETE (absorbed duplicates). */
        val droppedIds: List<Long>,
        val referenceMatches: Int,
        val nearDuplicates: Int,
    )

    /**
     * Collapses an existing table's duplicates in one pass. Rows are
     * clustered greedily in timestamp order; a row joins a cluster when it
     * duplicates ANY member (each pairing still passes the tier checks, so
     * the window never stretches transitively past a guard).
     */
    fun dedupe(rows: List<TransactionEntity>): DedupeResult {
        val updated = mutableListOf<TransactionEntity>()
        val droppedIds = mutableListOf<Long>()
        var tier1 = 0
        var tier2 = 0
        // Both tiers require equal amount+type+last4 — cluster inside those
        // buckets only, keeping the pass linear over a large table.
        val buckets = rows.groupBy { Triple(it.amount, it.type, it.accountNumber) }
        for (bucket in buckets.values) {
            if (bucket.size < 2) continue
            val clusters = mutableListOf<MutableList<TransactionEntity>>()
            val survivors = mutableListOf<TransactionEntity>()
            for (row in bucket.sortedBy { it.timestamp }) {
                val index = clusters.indexOfFirst { members -> members.any { isDuplicate(it, row) } }
                if (index < 0) {
                    clusters += mutableListOf(row)
                    survivors += row
                } else {
                    if (clusters[index].any { isReferenceDuplicate(it, row) }) tier1++ else tier2++
                    clusters[index] += row
                    survivors[index] = collapse(survivors[index], row)
                }
            }
            clusters.forEachIndexed { index, members ->
                if (members.size < 2) return@forEachIndexed
                val survivor = survivors[index]
                droppedIds += members.map { it.id }.filter { it != survivor.id }
                if (members.first { it.id == survivor.id } != survivor) updated += survivor
            }
        }
        return DedupeResult(updated, droppedIds, tier1, tier2)
    }
}
