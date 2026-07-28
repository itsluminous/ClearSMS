package app.clearsms.ui.finance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import app.clearsms.R

/**
 * Returns the eye-tap handler for gated balances and hosts the two
 * capability dialogs it can raise.
 *
 * Tap while revealed → conceal immediately (hiding needs no auth). Tap while
 * masked → consult [BalanceUnlock.decide]:
 * - [UnlockDecision.PROMPT]: system sheet; only success calls [onReveal].
 * - [UnlockDecision.NO_DEVICE_LOCK]: explains that a screen lock is required
 *   and offers the system security settings — no silent reveal, no dead end.
 * - [UnlockDecision.UNAVAILABLE]: honest "try again later" dialog.
 */
@Composable
fun balanceToggleHandler(
    revealed: Boolean,
    onReveal: () -> Unit,
    onConceal: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var blockedBy by remember { mutableStateOf<UnlockDecision?>(null) }
    val promptTitle = stringResource(R.string.balance_unlock_title)
    val promptSubtitle = stringResource(R.string.balance_unlock_subtitle)

    when (blockedBy) {
        UnlockDecision.NO_DEVICE_LOCK -> NoScreenLockDialog(onDismiss = { blockedBy = null })
        UnlockDecision.UNAVAILABLE -> AuthUnavailableDialog(onDismiss = { blockedBy = null })
        else -> Unit
    }

    return {
        if (revealed) {
            onConceal()
        } else {
            val status = BiometricManager.from(context).canAuthenticate(BalanceUnlock.AUTHENTICATORS)
            when (val decision = BalanceUnlock.decide(status)) {
                UnlockDecision.PROMPT -> {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        blockedBy = UnlockDecision.UNAVAILABLE
                    } else {
                        BalanceUnlock.prompt(activity, promptTitle, promptSubtitle) { ok ->
                            if (ok) onReveal()
                        }
                    }
                }
                else -> blockedBy = decision
            }
        }
    }
}

@Composable
private fun NoScreenLockDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.balance_no_lock_title)) },
        text = { Text(stringResource(R.string.balance_no_lock_text)) },
        confirmButton = {
            TextButton(
                onClick = {
                    // Some restricted profiles hide this screen; failing to
                    // resolve must not crash — the dialog text still explains.
                    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.balance_no_lock_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AuthUnavailableDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.balance_unavailable_title)) },
        text = { Text(stringResource(R.string.balance_unavailable_text)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/** Walks the context chain to the hosting [FragmentActivity], if any. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
