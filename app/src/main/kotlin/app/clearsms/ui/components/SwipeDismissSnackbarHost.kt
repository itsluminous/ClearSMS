package app.clearsms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

/**
 * The app's one snackbar host: a [SnackbarHost] whose snackbars can be swiped
 * away in either direction. Snackbars sit over the bottom of the screen -
 * exactly where a just-sent message appears - so waiting out the timeout to
 * read your own message was the only option before this.
 *
 * A swipe is a plain dismissal ([SnackbarData.dismiss]), never the action:
 * swiping an UNDO snackbar therefore means "no, keep the delete", which is
 * what the gesture reads as elsewhere too. Deletion timing does not depend on
 * this at all - the staged provider commit runs on the undo manager's own
 * schedule - so an early dismissal can neither lose nor resurrect a message.
 *
 * The swipe state is keyed on the snackbar's data so each new snackbar starts
 * at rest; a shared state would arrive already dismissed after the first
 * swipe and the next snackbar would never be visible.
 */
@Composable
fun SwipeDismissSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        key(data) {
            val swipeState = rememberSwipeToDismissBoxState()
            LaunchedEffect(swipeState.currentValue) {
                if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
                    data.dismiss()
                }
            }
            SwipeToDismissBox(
                state = swipeState,
                // Nothing is revealed behind a snackbar: it simply slides off.
                backgroundContent = { Box(modifier = Modifier) },
                content = { Snackbar(data) },
            )
        }
    }
}
