package app.clearsms.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level contracts for the pill-order dialog, in the repo's convention
 * (no Compose UI harness). Three things must never quietly regress:
 *
 * 1. ONE dialog: Inbox, Finance and Alerts all open the shared
 *    [PillOrderDialog]; nobody forks a copy.
 * 2. The gesture is a thin shell over the pure, unit-tested reorder logic
 *    ([PillDragState] / [movedPill]); the order is persisted from the
 *    completed-drag path, never per pixel.
 * 3. ACCESSIBILITY: the drag handle exposes "Move up" / "Move down" as
 *    custom accessibility actions and names its row - the up/down buttons
 *    the handle replaced were the screen-reader and switch-access path, and
 *    a drag-only reorder would lock those users out of Settings.
 */
class PillOrderDialogContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private val dialog = source("ui/settings/PillOrderDialog.kt")
    private val settings = source("ui/settings/SettingsScreen.kt")

    @Test
    fun `all three tabs open the one shared dialog, with no fork`() {
        assertThat(dialog).contains("fun <T> PillOrderDialog(")
        assertThat(settings.split("PillOrderDialog(").size - 1).isEqualTo(3)
        for (screen in listOf("INBOX", "FINANCE", "ALERTS")) {
            assertThat(settings).contains("SettingsDialog.${screen}_PILL_ORDER -> {")
        }
        assertThat(settings).contains("onOrderChange = viewModel::setInboxPillOrder,")
        assertThat(settings).contains("onOrderChange = viewModel::setFinancePillOrder,")
        assertThat(settings).contains("onOrderChange = viewModel::setAlertsPillOrder,")
        // The only composable that declares a reorder handle or its gesture.
        val ui = File("src/main/kotlin/app/clearsms/ui").walk().filter { it.extension == "kt" }.toList()
        val handles = ui.filter { "Icons.Default.DragIndicator" in it.readText() }.map { it.name }
        assertThat(handles).containsExactly("PillOrderDialog.kt")
        val gestures = ui.filter { "detectDragGestures(" in it.readText() }.map { it.name }
        assertThat(gestures).containsExactly("PillOrderDialog.kt")
    }

    @Test
    fun `the handle is a dotted grid on the left and the arrows are gone`() {
        assertThat(dialog).contains("Icons.Default.DragIndicator")
        assertThat(dialog).doesNotContain("KeyboardArrowUp")
        assertThat(dialog).doesNotContain("KeyboardArrowDown")
        // Handle first, label after: the handle sits to the LEFT of the row.
        val handle = dialog.indexOf("imageVector = Icons.Default.DragIndicator")
        val name = dialog.indexOf("Text(text = name, style = MaterialTheme.typography.bodyLarge)")
        assertThat(handle).isGreaterThan(-1)
        assertThat(name).isGreaterThan(handle)
        // The dialog still scrolls when taller than the screen.
        assertThat(dialog).contains("Column(Modifier.verticalScroll(rememberScrollState()))")
        // Reset survives; Done just closes (the order is already persisted).
        assertThat(dialog).contains("TextButton(onClick = onReset) { Text(stringResource(R.string.pill_order_reset)) }")
        assertThat(dialog).contains("TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }")
    }

    @Test
    fun `the gesture is a thin shell - every reorder goes through the pure state machine`() {
        assertThat(dialog).contains("mutableStateOf(PillDragState(order))")
        // One state holder for the dialog's life. The gesture coroutines keep
        // whatever MutableState they captured when they started, so re-keying
        // the holder on `order` (remember(order) { ... }) made the second drag
        // in a dialog operate on a stale copy and commit a corrupted order.
        assertThat(dialog).contains("var drag by remember { mutableStateOf(PillDragState(order)) }")
        assertThat(dialog).doesNotContain("remember(order) {")
        assertThat(dialog).contains("LaunchedEffect(order) {")
        assertThat(dialog).contains("if (!drag.isDragging && drag.order != order) drag = PillDragState(order)")
        assertThat(dialog).contains("onDragStart = { drag = drag.begin(index) },")
        assertThat(dialog).contains("drag = drag.dragBy(delta.y, height.toFloat())")
        assertThat(dialog).contains("onDragEnd = { settle(drag.finish()) },")
        assertThat(dialog).contains("onDragCancel = { drag = drag.cancel() },")
        // Persistence: onOrderChange is invoked from settle() only - i.e. from a
        // finished drag or an accessibility action - never inside onDrag.
        assertThat(dialog).contains("settled.committed?.let(onOrderChange)")
        val body = dialog.substringAfter("fun <T> PillOrderDialog(")
        assertThat(body.split("onOrderChange").size - 1).isEqualTo(2) // the parameter and settle()
        val onDrag = dialog.substringAfter("onDrag = { change, delta ->").substringBefore("onDragEnd")
        assertThat(onDrag).doesNotContain("onOrderChange")
        assertThat(onDrag).doesNotContain("settle(")
        // No second copy of the move arithmetic anywhere in the dialog.
        assertThat(dialog).doesNotContain("removeAt(")
        assertThat(dialog).doesNotContain("add(to")
        // Visual feedback: the dragged row lifts and follows the finger.
        assertThat(dialog).contains("translationY = if (dragging) drag.offsetPx else 0f")
        assertThat(dialog).contains("shadowElevation = if (dragging) 6.dp.toPx() else 0f")
        assertThat(dialog).contains(".zIndex(if (dragging) 1f else 0f)")
    }

    @Test
    fun `accessibility - the handle names its row and exposes Move up and Move down actions`() {
        // A content description naming the row it moves ("Reorder Important").
        assertThat(dialog).contains("val handle = stringResource(R.string.pill_order_drag_handle, name)")
        assertThat(dialog).contains("contentDescription = handle")
        assertThat(dialog).contains("stateDescription = position")
        // Semantic custom actions, so TalkBack / switch access can reorder
        // without a drag - the path the removed arrows used to provide.
        assertThat(dialog).contains("import androidx.compose.ui.semantics.CustomAccessibilityAction")
        assertThat(dialog).contains("import androidx.compose.ui.semantics.customActions")
        assertThat(dialog).contains("customActions =")
        assertThat(dialog).contains("CustomAccessibilityAction(moveUp) {")
        assertThat(dialog).contains("CustomAccessibilityAction(moveDown) {")
        assertThat(dialog).contains("settle(drag.moveBy(index, -1))")
        assertThat(dialog).contains("settle(drag.moveBy(index, +1))")
        assertThat(dialog).contains("val moveUp = stringResource(R.string.pill_order_move_up, name)")
        assertThat(dialog).contains("val moveDown = stringResource(R.string.pill_order_move_down, name)")
        // The handle is a real focusable node, so keyboard and switch users reach it.
        assertThat(dialog).contains(".focusable()")
        // And the strings the actions are built from exist.
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("<string name=\"pill_order_move_up\">Move %1\$s up</string>")
        assertThat(strings).contains("<string name=\"pill_order_move_down\">Move %1\$s down</string>")
        assertThat(strings).contains("<string name=\"pill_order_drag_handle\">Reorder %1\$s</string>")
        assertThat(strings).contains("<string name=\"pill_order_position\">Position %1\$d of %2\$d</string>")
    }

    @Test
    fun `renaming pills is gone - no label preference, catalog entry, backup entry or UI remains`() {
        val main = File("src/main").walk().filter { it.extension == "kt" || it.extension == "xml" }.toList()
        val offenders =
            main
                .filter { file ->
                    val text = file.readText()
                    listOf(
                        "inbox_pill_labels",
                        "INBOX_PILL_LABELS",
                        "InboxPillLabels",
                        "inboxPillLabels",
                        "PillLabelsDialog",
                        "Rename pills",
                    ).any { it in text }
                }.map { it.path }
        assertThat(offenders).isEmpty()
        assertThat(File("src/main/kotlin/app/clearsms/domain/model/InboxPillLabels.kt").exists()).isFalse()
        // The pill row and both pill dialogs render the built-in names directly.
        assertThat(source("ui/inbox/InboxScreen.kt")).contains("label = { Text(pill.defaultLabel()) },")
        assertThat(settings.split("label = { it.defaultLabel() },").size - 1).isEqualTo(2)
        assertThat(source("ui/inbox/InboxPillConfig.kt")).doesNotContain("labels")
    }
}
