package app.clearsms.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.clearsms.data.repository.PhoneNumberKey
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.data.repository.ThreadIdentity

/**
 * Re-keys every conversation in the `messages` table under the current
 * thread-identity rules ([ThreadIdentity]): recomputes `normalizedSender`
 * with the region-aware normalizer and merges the app threads that the
 * sender key or the provider thread now prove to be one person - the one-
 * time repair for issue #42 (the Polish `+48…` / local split), run by the
 * v21→v22 migration and again after every backup restore (a backup made
 * under the old key carries the old thread ids).
 *
 * **In place, from the stored rows.** Nothing is re-read from the provider
 * here (the migration backfills `providerThreadId` first, separately); a
 * row whose provider row is gone simply has no anchor and is keyed by its
 * sender alone. What each row keeps: its `id`, `sender`, body, read /
 * archived / blocked flags, delivery state, SIM, dates - every column but
 * `threadId` and `normalizedSender`.
 *
 * Grouping = union-find over the rows: rows of the same existing thread stay
 * together; rows with the same new sender key are one thread; rows with the
 * same provider thread are one thread IF their keys are loosely the same
 * number ([PhoneNumberKey.looselySame] - the same group-thread guard the
 * live path applies, so a provider thread holding two people never merges
 * them). Threads only ever MERGE here, never split: a conversation the old
 * ten-digit key fused wrongly (two numbers sharing their last ten digits)
 * stays as it is - the status quo, not a regression - because taking a
 * thread apart on a device whose region is unknown could split a real
 * person in two.
 *
 * Each group keeps the SMALLEST of its thread ids, so an unchanged
 * conversation keeps its id (drafts, pins and navigation state all still
 * point at it) and a merged one lands on the id its older half already
 * had. Then:
 * - `drafts`: the merged group's newest draft moves to the surviving id,
 *   older ones are dropped (one thread, one draft);
 * - `thread_pins`: a pin on any old key of a group is re-keyed to every
 *   new key the group's rows carry (earliest pinnedAt wins), and old keys
 *   no row carries any more are deleted - a pinned half stays pinned;
 * - read / archived state lives on the rows and travels with them.
 *
 * Idempotent: a second run computes the same keys and finds every group
 * already on one id, so it writes nothing.
 */
object ThreadRekeyer {
    /** What a run changed - for the migration log and the tests. */
    data class Result(
        val rows: Int,
        val rekeyedSenders: Int,
        val mergedThreads: Int,
        val movedRows: Int,
    )

    private class Row(
        val id: Long,
        val threadId: Long,
        val sender: String,
        val oldKey: String,
        val newKey: String,
        val anchor: Long?,
    )

    fun rekey(
        db: SupportSQLiteDatabase,
        region: String? = SenderNormalizer.defaultRegion,
    ): Result {
        val rows = ArrayList<Row>()
        db.query("SELECT id, threadId, sender, normalizedSender, providerThreadId FROM messages").use { cursor ->
            while (cursor.moveToNext()) {
                val sender = cursor.getString(2)
                rows +=
                    Row(
                        id = cursor.getLong(0),
                        threadId = cursor.getLong(1),
                        sender = sender,
                        oldKey = cursor.getString(3),
                        newKey = SenderNormalizer.normalize(sender, region),
                        anchor =
                            if (cursor.isNull(4)) {
                                null
                            } else {
                                ThreadIdentity.anchorFor(sender, cursor.getLong(4))
                            },
                    )
            }
        }
        if (rows.isEmpty()) return Result(0, 0, 0, 0)

        // 1. Sender keys: one UPDATE per distinct sender whose key moved.
        val movedSenders = LinkedHashMap<String, String>()
        for (row in rows) if (row.newKey != row.oldKey) movedSenders[row.sender] = row.newKey
        for ((sender, key) in movedSenders) {
            db.execSQL("UPDATE messages SET normalizedSender = ? WHERE sender = ?", arrayOf<Any?>(key, sender))
        }

        // 2. Groups (union-find over row indices).
        val parent = IntArray(rows.size) { it }

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(
            a: Int,
            b: Int,
        ) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val byOldThread = HashMap<Long, Int>()
        rows.forEachIndexed { i, row ->
            val first = byOldThread.putIfAbsent(row.threadId, i)
            if (first != null) union(i, first)
        }
        val byKey = HashMap<String, Int>()
        rows.forEachIndexed { i, row ->
            if (row.newKey.isEmpty()) return@forEachIndexed
            val first = byKey.putIfAbsent(row.newKey, i)
            if (first != null) union(i, first)
        }
        val byAnchor = HashMap<Long, MutableList<Int>>()
        rows.forEachIndexed { i, row ->
            val anchor = row.anchor ?: return@forEachIndexed
            val members = byAnchor.getOrPut(anchor) { ArrayList(2) }
            // Same guard as the live path: an anchor joins two keys only when
            // they are loosely the same number - never two people.
            val mate = members.firstOrNull { PhoneNumberKey.looselySame(rows[it].newKey, row.newKey) }
            if (mate != null) union(i, mate)
            members += i
        }

        // 3. Survivor id per group = smallest thread id in the group (every
        //    old thread lies wholly inside one group, so this is well-defined).
        val survivor = HashMap<Int, Long>()
        for (i in rows.indices) {
            val root = find(i)
            val current = survivor[root]
            if (current == null || rows[i].threadId < current) survivor[root] = rows[i].threadId
        }

        // 4. Whole threads move onto their survivor: one UPDATE per absorbed
        //    thread, never per row.
        val rowsByOldThread = rows.indices.groupBy { rows[it].threadId }
        val absorbed = HashMap<Long, Long>()
        var movedRows = 0
        for ((oldThread, members) in rowsByOldThread) {
            val target = survivor.getValue(find(members.first()))
            if (target == oldThread) continue
            db.execSQL("UPDATE messages SET threadId = ? WHERE threadId = ?", arrayOf<Any?>(target, oldThread))
            absorbed[oldThread] = target
            movedRows += members.size
        }

        // 5. Drafts of absorbed threads: newest survives on the survivor id.
        if (absorbed.isNotEmpty()) mergeDrafts(db, absorbed)

        // 6. Pins: re-key by group membership (see class doc).
        rekeyPins(db, rows, ::find)

        return Result(
            rows = rows.size,
            rekeyedSenders = movedSenders.size,
            mergedThreads = absorbed.size,
            movedRows = movedRows,
        )
    }

    private fun mergeDrafts(
        db: SupportSQLiteDatabase,
        absorbed: Map<Long, Long>,
    ) {
        class Draft(
            val threadId: Long,
            val text: String,
            val updatedAt: Long,
        )
        val involved = (absorbed.keys + absorbed.values).toSet()
        val drafts = ArrayList<Draft>()
        for (chunk in involved.chunked(SQL_CHUNK)) {
            db
                .query(
                    "SELECT threadId, text, updatedAt FROM drafts WHERE threadId IN (${chunk.joinToString(",")})",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        drafts += Draft(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
                    }
                }
        }
        if (drafts.isEmpty()) return
        val bySurvivor = drafts.groupBy { absorbed[it.threadId] ?: it.threadId }
        for ((target, group) in bySurvivor) {
            val newest = group.maxBy { it.updatedAt }
            for (draft in group) {
                if (draft.threadId != target) db.execSQL("DELETE FROM drafts WHERE threadId = ?", arrayOf<Any?>(draft.threadId))
            }
            db.execSQL(
                "INSERT OR REPLACE INTO drafts (threadId, text, updatedAt) VALUES (?, ?, ?)",
                arrayOf<Any?>(target, newest.text, newest.updatedAt),
            )
        }
    }

    private fun rekeyPins(
        db: SupportSQLiteDatabase,
        rows: List<Row>,
        find: (Int) -> Int,
    ) {
        val pins = LinkedHashMap<String, Long>()
        db.query("SELECT normalizedSender, pinnedAt FROM thread_pins").use { cursor ->
            while (cursor.moveToNext()) pins[cursor.getString(0)] = cursor.getLong(1)
        }
        if (pins.isEmpty()) return
        // old key -> groups it belonged to; group -> new keys it carries now.
        val groupsOfOldKey = HashMap<String, MutableSet<Int>>()
        val newKeysOfGroup = HashMap<Int, MutableSet<String>>()
        val liveKeys = HashSet<String>()
        rows.forEachIndexed { i, row ->
            val root = find(i)
            groupsOfOldKey.getOrPut(row.oldKey) { HashSet(1) } += root
            newKeysOfGroup.getOrPut(root) { HashSet(1) } += row.newKey
            liveKeys += row.newKey
        }
        val upserts = HashMap<String, Long>()
        val deletes = ArrayList<String>()
        for ((oldKey, pinnedAt) in pins) {
            val groups = groupsOfOldKey[oldKey]
            if (groups == null) continue // orphan pin (no rows): left alone
            for (root in groups) {
                for (newKey in newKeysOfGroup.getValue(root)) {
                    if (newKey == oldKey) continue
                    val existing = upserts[newKey] ?: pins[newKey]
                    if (existing == null || pinnedAt < existing) upserts[newKey] = pinnedAt
                }
            }
            if (oldKey !in liveKeys) deletes += oldKey
        }
        for ((key, pinnedAt) in upserts) {
            db.execSQL(
                "INSERT OR REPLACE INTO thread_pins (normalizedSender, pinnedAt) VALUES (?, ?)",
                arrayOf<Any?>(key, pinnedAt),
            )
        }
        for (key in deletes) db.execSQL("DELETE FROM thread_pins WHERE normalizedSender = ?", arrayOf<Any?>(key))
    }

    private const val SQL_CHUNK = 500
}
