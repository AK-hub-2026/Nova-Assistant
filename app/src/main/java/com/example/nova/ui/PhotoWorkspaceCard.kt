package com.example.nova.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.NovaPhotoItem
import com.example.nova.core.PhotoAccessMode
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.PhotoEntrySource
import com.example.nova.core.UriPersistenceState
import com.example.nova.tools.FullscreenPhotoLoadOutcome
import com.example.nova.tools.ThumbnailDecodeOutcome
import com.example.ui.theme.NovaOrbCyanGlow
import com.example.ui.theme.NovaOrbSapphireCore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formatted display strings for a [NovaPhotoItem].
 * Guarantees that any missing metadata field is represented as `"Unavailable"`
 * and never substituted with a fake filename, fake date, or fake dimensions.
 */
data class PhotoItemDisplayMetadata(
    val displayNameText: String,
    val dateText: String,
    val dimensionsText: String,
    val mimeTypeText: String,
    val accessBadgeText: String
)

fun formatPhotoItemDisplayMetadata(item: NovaPhotoItem): PhotoItemDisplayMetadata {
    val nameText = when (val n = item.displayName) {
        is DeviceFieldState.Available -> n.value
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val dateText = when (val d = item.dateTakenOrAddedMillis) {
        is DeviceFieldState.Available -> {
            if (d.value > 0L) {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(d.value))
                }.getOrDefault("Unavailable")
            } else {
                "Unavailable"
            }
        }
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val w = item.widthPx.valueOrNull()
    val h = item.heightPx.valueOrNull()
    val dimensionsText = if (w != null && h != null && w > 0 && h > 0) {
        "${w}×${h}"
    } else {
        "Unavailable"
    }

    val mimeText = when (val m = item.mimeType) {
        is DeviceFieldState.Available -> m.value
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val badgeText = when (item.source) {
        PhotoEntrySource.MEDIASTORE_QUERY -> "MediaStore"
        PhotoEntrySource.PHOTO_PICKER_PERSISTED -> "Picker (Persisted)"
        PhotoEntrySource.PHOTO_PICKER_SESSION -> when (item.uriPersistenceState) {
            UriPersistenceState.PERSISTABLE_GRANTED -> "Picker (Persisted)"
            UriPersistenceState.ACCESS_REVOKED_OR_EXPIRED -> "Access Revoked"
            else -> "Picker (Session Only)"
        }
    }

    return PhotoItemDisplayMetadata(
        displayNameText = nameText,
        dateText = dateText,
        dimensionsText = dimensionsText,
        mimeTypeText = mimeText,
        accessBadgeText = badgeText
    )
}

/**
 * M5 — Contextual Photo / Media Access Workspace Card.
 *
 * Renders inside the Nova assistant workspace when a photo command is active.
 * Supports all 6 real states:
 * 1. Real photos available (MediaStore, Picker Session, or Picker Persisted)
 * 2. Permission required
 * 3. Picker required
 * 4. Empty media library
 * 5. Unsupported Android/device capability
 * 6. Error
 */
@Composable
fun PhotoWorkspaceCard(
    photoState: PhotoAccessWorkspaceState,
    lowRamMode: Boolean,
    onLoadThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome,
    onLoadFullscreenPhoto: suspend (String, Boolean) -> FullscreenPhotoLoadOutcome,
    onRequestMediaPermission: () -> Unit,
    onLaunchPhotoPicker: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPhotoForViewer by remember { mutableStateOf<NovaPhotoItem?>(null) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = NovaOrbCyanGlow.copy(alpha = 0.28f),
                shape = RoundedCornerShape(22.dp)
            )
            .testTag("photo_workspace_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = NovaOrbSapphireCore
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = resolvePhotoWorkspaceTitle(photoState),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = resolvePhotoAccessBadge(photoState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_workspace_card_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss photo workspace card"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (photoState.accessMode) {
                PhotoAccessMode.MEDIASTORE_ACCESS,
                PhotoAccessMode.PICKED_URI_SESSION,
                PhotoAccessMode.PICKED_URI_PERSISTED -> {
                    if (photoState.photos.isEmpty()) {
                        EmptyPhotoLibraryContent(
                            statusMessage = photoState.statusMessage,
                            isPhotoPickerSupported = photoState.isPhotoPickerSupported,
                            onLaunchPhotoPicker = onLaunchPhotoPicker
                        )
                    } else {
                        RealPhotosAvailableContent(
                            photoState = photoState,
                            lowRamMode = lowRamMode,
                            onLoadThumbnail = onLoadThumbnail,
                            onPhotoClick = { item -> selectedPhotoForViewer = item },
                            onLaunchPhotoPicker = onLaunchPhotoPicker,
                            onRequestMediaPermission = onRequestMediaPermission
                        )
                    }
                }

                PhotoAccessMode.PERMISSION_REQUIRED -> {
                    if (photoState.isPickerLaunchRequested) {
                        PickerRequiredContent(
                            statusMessage = photoState.statusMessage,
                            isPhotoPickerSupported = photoState.isPhotoPickerSupported,
                            onLaunchPhotoPicker = onLaunchPhotoPicker,
                            onRequestMediaPermission = onRequestMediaPermission
                        )
                    } else {
                        PermissionRequiredPhotoContent(
                            statusMessage = photoState.statusMessage,
                            isPhotoPickerSupported = photoState.isPhotoPickerSupported,
                            onRequestMediaPermission = onRequestMediaPermission,
                            onLaunchPhotoPicker = onLaunchPhotoPicker
                        )
                    }
                }

                PhotoAccessMode.EMPTY -> {
                    EmptyPhotoLibraryContent(
                        statusMessage = photoState.statusMessage,
                        isPhotoPickerSupported = photoState.isPhotoPickerSupported,
                        onLaunchPhotoPicker = onLaunchPhotoPicker
                    )
                }

                PhotoAccessMode.UNAVAILABLE -> {
                    UnsupportedPhotoCapabilityContent(
                        statusMessage = photoState.statusMessage
                    )
                }

                PhotoAccessMode.ERROR -> {
                    ErrorPhotoWorkspaceContent(
                        errorMessage = photoState.errorMessage ?: photoState.statusMessage,
                        isPhotoPickerSupported = photoState.isPhotoPickerSupported,
                        onLaunchPhotoPicker = onLaunchPhotoPicker
                    )
                }
            }
        }
    }

    val activeViewerItem = selectedPhotoForViewer
    if (activeViewerItem != null) {
        FullscreenPhotoViewerDialog(
            photoItem = activeViewerItem,
            lowRamMode = lowRamMode,
            onLoadFullscreenPhoto = onLoadFullscreenPhoto,
            onDismiss = { selectedPhotoForViewer = null }
        )
    }
}

private fun resolvePhotoWorkspaceTitle(state: PhotoAccessWorkspaceState): String {
    return when (state.accessMode) {
        PhotoAccessMode.MEDIASTORE_ACCESS ->
            if (state.isSelectiveMediaAccess) "Selected Device Photos" else "Recent Device Photos"
        PhotoAccessMode.PICKED_URI_PERSISTED -> "Picked Photos (Persisted)"
        PhotoAccessMode.PICKED_URI_SESSION -> "Picked Photos (Session)"
        PhotoAccessMode.PERMISSION_REQUIRED ->
            if (state.isPickerLaunchRequested) "Select Photos" else "Photo Access Required"
        PhotoAccessMode.EMPTY -> "No Accessible Photos"
        PhotoAccessMode.UNAVAILABLE -> "Photo Access Unavailable"
        PhotoAccessMode.ERROR -> "Photo Access Error"
    }
}

private fun resolvePhotoAccessBadge(state: PhotoAccessWorkspaceState): String {
    return when (state.accessMode) {
        PhotoAccessMode.MEDIASTORE_ACCESS ->
            if (state.isSelectiveMediaAccess) {
                "Access: MEDIASTORE_ACCESS (Android Selected Photos)"
            } else {
                "Access: MEDIASTORE_ACCESS"
            }
        PhotoAccessMode.PICKED_URI_PERSISTED -> "Access: PICKED_URI_PERSISTED"
        PhotoAccessMode.PICKED_URI_SESSION -> "Access: PICKED_URI_SESSION (Session-only)"
        PhotoAccessMode.PERMISSION_REQUIRED -> "Access: PERMISSION_REQUIRED"
        PhotoAccessMode.EMPTY -> "Access: EMPTY"
        PhotoAccessMode.UNAVAILABLE -> "Access: UNAVAILABLE"
        PhotoAccessMode.ERROR -> "Access: ERROR"
    }
}

@Composable
private fun RealPhotosAvailableContent(
    photoState: PhotoAccessWorkspaceState,
    lowRamMode: Boolean,
    onLoadThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome,
    onPhotoClick: (NovaPhotoItem) -> Unit,
    onLaunchPhotoPicker: () -> Unit,
    onRequestMediaPermission: () -> Unit
) {
    if (photoState.statusMessage.isNotBlank()) {
        Text(
            text = photoState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_workspace_lazy_row"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(
            items = photoState.photos,
            key = { it.contentUri }
        ) { item ->
            PhotoThumbnailTile(
                item = item,
                lowRamMode = lowRamMode,
                onLoadThumbnail = onLoadThumbnail,
                onClick = { onPhotoClick(item) }
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (photoState.accessMode != PhotoAccessMode.MEDIASTORE_ACCESS || photoState.isSelectiveMediaAccess) {
            OutlinedButton(
                onClick = onRequestMediaPermission,
                modifier = Modifier.testTag("photo_grant_permission_button")
            ) {
                Text(if (photoState.isSelectiveMediaAccess) "Manage Access" else "Grant Library Access")
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (photoState.isPhotoPickerSupported) {
            Button(
                onClick = onLaunchPhotoPicker,
                modifier = Modifier.testTag("photo_launch_picker_button")
            ) {
                Text("Pick Photos")
            }
        }
    }
}

@Composable
private fun PhotoThumbnailTile(
    item: NovaPhotoItem,
    lowRamMode: Boolean,
    onLoadThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome,
    onClick: () -> Unit
) {
    val meta = remember(item) { formatPhotoItemDisplayMetadata(item) }
    var thumbState by remember(item.contentUri) { mutableStateOf<ThumbnailDecodeOutcome?>(null) }

    LaunchedEffect(item.contentUri, lowRamMode) {
        thumbState = onLoadThumbnail(item.contentUri, lowRamMode)
    }

    Surface(
        modifier = Modifier
            .width(126.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("photo_thumbnail_tile"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                when (val current = thumbState) {
                    null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    is ThumbnailDecodeOutcome.Available -> {
                        val imageBitmap = remember(current.bitmap) {
                            current.bitmap.asImageBitmap()
                        }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = meta.displayNameText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("photo_thumbnail_image")
                        )
                    }
                    is ThumbnailDecodeOutcome.PreviewUnavailable -> {
                        Column(
                            modifier = Modifier
                                .padding(8.dp)
                                .testTag("photo_preview_unavailable_tile"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = "Preview unavailable",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Preview unavailable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = meta.displayNameText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (meta.dimensionsText != "Unavailable") meta.dimensionsText else meta.accessBadgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredPhotoContent(
    statusMessage: String,
    isPhotoPickerSupported: Boolean,
    onRequestMediaPermission: () -> Unit,
    onLaunchPhotoPicker: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_permission_required_state")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Photo access is required",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = statusMessage.ifBlank {
                "Grant Android photo permission to view recent MediaStore photos, or select individual photos with Android Photo Picker."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (isPhotoPickerSupported) {
                OutlinedButton(
                    onClick = onLaunchPhotoPicker,
                    modifier = Modifier.testTag("photo_launch_picker_button")
                ) {
                    Text("Use Photo Picker")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(
                onClick = onRequestMediaPermission,
                modifier = Modifier.testTag("photo_grant_permission_button")
            ) {
                Text("Grant Photo Permission")
            }
        }
    }
}

@Composable
private fun PickerRequiredContent(
    statusMessage: String,
    isPhotoPickerSupported: Boolean,
    onLaunchPhotoPicker: () -> Unit,
    onRequestMediaPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_picker_required_state")
    ) {
        Text(
            text = statusMessage.ifBlank { "Select photos using the Android Photo Picker." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onRequestMediaPermission,
                modifier = Modifier.testTag("photo_grant_permission_button")
            ) {
                Text("Grant Library Permission")
            }
            if (isPhotoPickerSupported) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onLaunchPhotoPicker,
                    modifier = Modifier.testTag("photo_launch_picker_button")
                ) {
                    Text("Open Photo Picker")
                }
            }
        }
    }
}

@Composable
private fun EmptyPhotoLibraryContent(
    statusMessage: String,
    isPhotoPickerSupported: Boolean,
    onLaunchPhotoPicker: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_empty_state")
    ) {
        Text(
            text = statusMessage.ifBlank { "No accessible photos were found on this device." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isPhotoPickerSupported) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onLaunchPhotoPicker,
                    modifier = Modifier.testTag("photo_launch_picker_button")
                ) {
                    Text("Select with Photo Picker")
                }
            }
        }
    }
}

@Composable
private fun UnsupportedPhotoCapabilityContent(
    statusMessage: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_unsupported_state"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusMessage.ifBlank { "Photo access capability is not supported on this device." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorPhotoWorkspaceContent(
    errorMessage: String,
    isPhotoPickerSupported: Boolean,
    onLaunchPhotoPicker: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_error_state")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = errorMessage.ifBlank { "An error occurred while accessing photos." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isPhotoPickerSupported) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onLaunchPhotoPicker,
                    modifier = Modifier.testTag("photo_launch_picker_button")
                ) {
                    Text("Pick Photos")
                }
            }
        }
    }
}

/**
 * M5 — Fullscreen Photo Viewer for inspecting a real [NovaPhotoItem] content URI.
 * Opens and decodes the actual content URI directly without copying files, and
 * reports a truthful error state if the URI has become inaccessible or revoked.
 */
@Composable
fun FullscreenPhotoViewerDialog(
    photoItem: NovaPhotoItem,
    lowRamMode: Boolean,
    onLoadFullscreenPhoto: suspend (String, Boolean) -> FullscreenPhotoLoadOutcome,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val meta = remember(photoItem) { formatPhotoItemDisplayMetadata(photoItem) }
    var loadOutcome by remember(photoItem.contentUri) { mutableStateOf<FullscreenPhotoLoadOutcome?>(null) }
    var externalViewerError by remember(photoItem.contentUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(photoItem.contentUri, lowRamMode) {
        externalViewerError = null
        loadOutcome = onLoadFullscreenPhoto(photoItem.contentUri, lowRamMode)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("fullscreen_photo_viewer"),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.96f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = meta.displayNameText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${meta.accessBadgeText} • ${meta.dimensionsText} • ${meta.dateText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val err = tryOpenPhotoInExternalViewer(context, photoItem)
                                externalViewerError = err
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("fullscreen_open_external_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in external viewer",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = CircleShape
                                )
                                .testTag("fullscreen_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close photo viewer",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Center Photo / Error Surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (val outcome = loadOutcome) {
                        null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.testTag("fullscreen_viewer_loading")
                            )
                        }
                        is FullscreenPhotoLoadOutcome.Available -> {
                            val imageBitmap = remember(outcome.bitmap) {
                                outcome.bitmap.asImageBitmap()
                            }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = meta.displayNameText,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("fullscreen_photo_image")
                            )
                        }
                        is FullscreenPhotoLoadOutcome.InaccessibleOrRevoked -> {
                            ViewerErrorCard(
                                title = "Photo URI Unavailable or Revoked",
                                detail = outcome.message
                            )
                        }
                        is FullscreenPhotoLoadOutcome.DecodeError -> {
                            ViewerErrorCard(
                                title = "Unable to Open Photo",
                                detail = outcome.message
                            )
                        }
                    }
                }

                // Bottom Metadata & External Error Notice
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!externalViewerError.isNullOrBlank()) {
                        Text(
                            text = externalViewerError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .testTag("fullscreen_external_viewer_error")
                        )
                    }
                    Text(
                        text = "MIME: ${meta.mimeTypeText} • Scope: ${meta.accessBadgeText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerErrorCard(
    title: String,
    detail: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("fullscreen_viewer_error_state"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun tryOpenPhotoInExternalViewer(
    context: Context,
    item: NovaPhotoItem
): String? {
    val uri = runCatching { Uri.parse(item.contentUri) }.getOrNull()
        ?: return "Invalid content URI."

    // Verify URI is still readable before dispatching ACTION_VIEW
    val readable = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.fileDescriptor != null } ?: false
    }.getOrDefault(false)
    if (!readable) {
        return "This photo URI is no longer accessible or permission was revoked."
    }

    val mime = item.mimeType.valueOrNull()?.takeIf { it.startsWith("image/") } ?: "image/*"
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val resolved = viewIntent.resolveActivity(context.packageManager)
        ?: return "No external gallery or image viewer app is installed to handle this URI."

    return try {
        context.startActivity(viewIntent)
        null
    } catch (e: Exception) {
        "Could not launch external viewer (${resolved.packageName}): ${e.message ?: e.javaClass.simpleName}"
    }
}
