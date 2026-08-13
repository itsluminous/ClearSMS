package app.clearsms.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.mms.MmsSizeLimits
import app.clearsms.mms.StagedAttachment
import app.clearsms.ui.common.AttachmentError
import app.clearsms.ui.conversation.humanSize
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable as composeClickable

/**
 * The launcher trio behind the compose-bar attach sheet: the modern
 * androidx photo picker (no storage permission), a camera capture into an
 * app-private FileProvider target, and the documents UI for any file.
 * All three land in the same `onPicked`/`onCameraDone` callbacks.
 */
class AttachmentLaunchers internal constructor(
    val pickPhotos: () -> Unit,
    val takePhoto: () -> Unit,
    val pickFile: () -> Unit,
)

@Composable
fun rememberAttachmentLaunchers(
    onPicked: (List<Uri>) -> Unit,
    cameraUriProvider: () -> Uri,
    onCameraDone: (Boolean) -> Unit,
): AttachmentLaunchers {
    val context = LocalContext.current
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            onPicked(uris)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            onCameraDone(success)
        }
    val documents =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onPicked(listOfNotNull(uri))
        }
    val cameraMissing = stringResource(R.string.compose_camera_missing)
    return AttachmentLaunchers(
        pickPhotos = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        takePhoto = {
            val target = cameraUriProvider()
            try {
                camera.launch(target)
            } catch (_: ActivityNotFoundException) {
                onCameraDone(false)
                Toast.makeText(context, cameraMissing, Toast.LENGTH_SHORT).show()
            }
        },
        pickFile = { documents.launch(arrayOf("*/*")) },
    )
}

/** The attach bottom sheet: Photo (photo picker), Camera, Any file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    launchers: AttachmentLaunchers,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetOption(Icons.Outlined.Image, stringResource(R.string.compose_attach_photo)) {
            onDismiss()
            launchers.pickPhotos()
        }
        SheetOption(Icons.Outlined.PhotoCamera, stringResource(R.string.compose_attach_camera)) {
            onDismiss()
            launchers.takePhoto()
        }
        SheetOption(Icons.Outlined.AttachFile, stringResource(R.string.compose_attach_file)) {
            onDismiss()
            launchers.pickFile()
        }
    }
}

@Composable
private fun SheetOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        modifier = Modifier.composeClickable(onClick = onClick),
    )
}

/**
 * Staged attachments above the compose field: removable thumbnails for
 * images, name chips for files, plus the running size indicator against
 * the carrier budget.
 */
@Composable
fun AttachmentChipsRow(
    attachments: List<StagedAttachment>,
    onRemove: (StagedAttachment) -> Unit,
    error: AttachmentError?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(attachments, key = { it.id }) { attachment ->
                AttachmentChip(attachment, onRemove)
            }
        }
        val total = attachments.sumOf { it.sizeBytes }
        Text(
            text =
                stringResource(
                    R.string.compose_attachment_size,
                    humanSize(total),
                    humanSize(MmsSizeLimits.TOTAL_BUDGET_BYTES),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        AttachmentErrorText(error)
    }
}

/** The honest inline error for a refused attachment; null renders nothing. */
@Composable
fun AttachmentErrorText(error: AttachmentError?) {
    if (error == null) return
    Text(
        text =
            when (error) {
                AttachmentError.TOO_LARGE ->
                    stringResource(R.string.compose_attachment_too_large, humanSize(MmsSizeLimits.TOTAL_BUDGET_BYTES))
                AttachmentError.UNREADABLE -> stringResource(R.string.compose_attachment_unreadable)
            },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun AttachmentChip(
    attachment: StagedAttachment,
    onRemove: (StagedAttachment) -> Unit,
) {
    Box {
        if (attachment.isImage) {
            AsyncImage(
                model = attachment.file,
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp).widthIn(max = 160.dp),
                ) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = attachment.displayName,
                            style = MaterialTheme.typography.bodySmall,
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
        // Remove badge riding the chip's corner.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .composeClickable(
                        onClick = { onRemove(attachment) },
                        onClickLabel = stringResource(R.string.compose_attachment_remove),
                    ),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.compose_attachment_remove),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}
