package app.clearsms.notification

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source contract: every notifier consults its section gate.
 *
 * Disabling a section (Inbox / Finance / Alerts) must silence that
 * section's notifications AT THE SOURCE, so each `*Notifier` in this
 * package is required to reference [NotificationSectionGate] - a future
 * notifier added without the gate fails here instead of quietly notifying
 * for a screen the user switched off.
 *
 * Posting OUTSIDE the notifiers is only allowed on the documented exemption
 * list: notifications that are progress/feedback of the user's OWN action,
 * not section content.
 */
class NotificationSectionConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    /** Files allowed to post without consulting the section gate, and why. */
    private val exempt =
        setOf(
            // Progress of the user-initiated initial import / full re-sort:
            // an operation status, not section content.
            "work/InitialSyncWorker.kt",
            "work/RecategorizeWorker.kt",
        )

    private fun postsNotifications(source: String): Boolean =
        source.contains("NotificationManagerCompat") && Regex("""\.notify\(""").containsMatchIn(source)

    @Test
    fun `every notifier class consults the section gate before posting`() {
        val notifiers =
            File(srcRoot, "notification")
                .listFiles { file -> file.name.endsWith("Notifier.kt") }!!
                .sortedBy { it.name }
        assertThat(notifiers).isNotEmpty()
        for (file in notifiers) {
            val source = file.readText()
            if (!postsNotifications(source)) continue
            assertWithMessage(
                "${file.name} posts notifications but never references NotificationSectionGate. " +
                    "Every notifier must consult its section's gate (see NotificationSectionGate) " +
                    "so a disabled section stays silent.",
            ).that(source).contains("sectionGate")
        }
    }

    @Test
    fun `nothing outside the notifiers posts a notification without the gate or a documented exemption`() {
        val offenders =
            srcRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.parentFile.name == "notification" && it.name.endsWith("Notifier.kt") }
                .filter { postsNotifications(it.readText()) }
                .map { it.relativeTo(srcRoot).path }
                .filterNot { it in exempt }
                .toList()
        assertWithMessage(
            "These files post notifications directly. Either route through a gated notifier in " +
                "app.clearsms.notification, or add the file to the exemption list WITH a justification " +
                "for why its notification belongs to no section: $offenders",
        ).that(offenders).isEmpty()
    }
}
