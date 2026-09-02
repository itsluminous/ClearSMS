package app.clearsms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import app.clearsms.R

/**
 * The app's one snackbar host: a [SnackbarHost] whose snackbars can be swiped
 * away in either direction and whose background is deliberately translucent,
 * so the message underneath stays partly visible instead of being covered.
 *
 * At [SNACKBAR_CONTAINER_ALPHA] the background alone is no longer enough to
 * guarantee contrast - whatever shows through can be light or dark - so the
 * label and action carry a soft shadow ([snackbarTextShadow]). That is why
 * this host renders the snackbar's content itself rather than handing
 * [SnackbarHostState] straight to the default `Snackbar(data)`, which draws
 * its own unstyled text: the action button and the dismiss affordance are
 * rebuilt here so nothing is lost by taking over.
 *
 * Taps on the action still go through [SnackbarData.performAction]; a SWIPE is
 * always a plain dismissal, never the action, so swiping an UNDO snackbar
 * means "keep the delete". Deletion timing does not depend on this at all -
 * the staged provider commit runs on the undo manager's own schedule.
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
            val contentColor = MaterialTheme.colorScheme.inverseOnSurface
            val actionColor = MaterialTheme.colorScheme.inversePrimary
            val shadowedStyle = LocalTextStyle.current.withSnackbarShadow()
            SwipeToDismissBox(
                state = swipeState,
                // Nothing is revealed behind a snackbar: it simply slides off.
                backgroundContent = { Box(modifier = Modifier) },
                content = {
                    Snackbar(
                        containerColor =
                            MaterialTheme.colorScheme.inverseSurface
                                .copy(alpha = SNACKBAR_CONTAINER_ALPHA),
                        contentColor = contentColor,
                        action =
                            data.visuals.actionLabel?.let { label ->
                                {
                                    TextButton(onClick = { data.performAction() }) {
                                        Text(text = label, color = actionColor, style = shadowedStyle)
                                    }
                                }
                            },
                        dismissAction =
                            if (data.visuals.withDismissAction) {
                                {
                                    TooltipIconButton(
                                        label = stringResource(R.string.alerts_dismiss),
                                        onClick = { data.dismiss() },
                                        icon = Icons.Outlined.Close,
                                        tint = contentColor,
                                    )
                                }
                            } else {
                                null
                            },
                    ) {
                        Text(text = data.visuals.message, style = shadowedStyle)
                    }
                },
            )
        }
    }
}

/**
 * Opacity of the snackbar background: enough of the message underneath shows
 * through to keep your place, which is the whole point of not covering it.
 * The text stays readable at this level only because of
 * [snackbarTextShadow] - drop the shadow and this must go back up.
 */
const val SNACKBAR_CONTAINER_ALPHA = 0.70f

/**
 * A soft dark halo behind snackbar text. With a translucent background the
 * text can end up over anything - a pale bubble, a photo thumbnail - and a
 * plain light glyph would dissolve into it. A blurred shadow with no offset
 * reads as a halo rather than a drop shadow, which keeps the label crisp at
 * small sizes.
 */
fun snackbarTextShadow(): Shadow =
    Shadow(
        color = Color.Black.copy(alpha = 0.8f),
        offset = Offset.Zero,
        blurRadius = 6f,
    )

/** Applies [snackbarTextShadow] to a text style. */
fun TextStyle.withSnackbarShadow(): TextStyle = copy(shadow = snackbarTextShadow())
