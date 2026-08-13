package app.clearsms.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.clearsms.R
import app.clearsms.ShareIntents
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.mms.mmsAttachmentFile
import coil.compose.AsyncImage
import java.io.File
import java.util.Locale

/**
 * MMS attachment rendering inside a conversation bubble: image parts as
 * tappable thumbnails (tap -> full-screen [MmsImageViewerDialog]), other
 * parts as a file chip (tap -> ACTION_VIEW through a FileProvider grant).
 * Coil - already in the app for contact photos - decodes the stored files;
 * no new image dependency.
 */
@Composable
internal fun MmsAttachmentContent(
    attachments: List<AttachmentEntity>,
    onImageTap: (AttachmentEntity) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (attachment in attachments) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachmentFile(context, attachment),
                    contentDescription = stringResource(R.string.mms_image_content_description),
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageTap(attachment) },
                )
            } else {
                AttachmentChip(attachment) { openAttachment(context, attachment) }
            }
        }
    }
}

/** Name + human size chip for a non-image attachment. */
@Composable
private fun AttachmentChip(
    attachment: AttachmentEntity,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    // Stored names carry a collision-proof index prefix
                    // ("0-photo.jpg"); show the human part.
                    text = attachment.fileName.substringAfter('-'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = humanSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Full-screen viewer for a tapped MMS image with share (via the app's
 * single [ShareIntents] chooser path) and close controls.
 */
@Composable
internal fun MmsImageViewerDialog(
    attachment: AttachmentEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onDismiss),
        ) {
            AsyncImage(
                model = attachmentFile(context, attachment),
                contentDescription = stringResource(R.string.mms_image_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton(onClick = { shareAttachment(context, attachment) }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.mms_viewer_share),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.mms_viewer_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private fun attachmentFile(
    context: Context,
    attachment: AttachmentEntity,
): File = mmsAttachmentFile(context.filesDir, attachment.messageId, attachment.fileName)

private fun attachmentUri(
    context: Context,
    attachment: AttachmentEntity,
): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", attachmentFile(context, attachment))

private fun shareAttachment(
    context: Context,
    attachment: AttachmentEntity,
) {
    context.startActivity(
        ShareIntents.fileChooser(
            uri = attachmentUri(context, attachment),
            mimeType = attachment.mimeType,
            title = context.getString(R.string.mms_viewer_share),
        ),
    )
}

/** ACTION_VIEW with a read grant; a missing viewer degrades to a toast. */
private fun openAttachment(
    context: Context,
    attachment: AttachmentEntity,
) {
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(attachmentUri(context, attachment), attachment.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.mms_open_attachment_failed, Toast.LENGTH_SHORT).show()
    }
}

/** 12345 -> "12.1 KB" - compact, locale-stable size label. */
internal fun humanSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
