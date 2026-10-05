package com.example.nova.tools

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.MediaStorePhotoEntry
import com.example.nova.core.MediaStoreQueryAccessState
import com.example.nova.core.NovaPhotoItem
import com.example.nova.core.PhotoAccessMode
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.PhotoEntrySource
import com.example.nova.core.PickedPhotoUriEntry
import com.example.nova.core.UriPersistenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Raw media metadata record read from Android [ContentResolver] or [MediaStore.Images].
 * Null values indicate columns that were absent, null, or undetermined by the provider;
 * they are mapped into [DeviceFieldState.Unavailable] and never replaced with fake defaults.
 */
data class RawMediaRecord(
    val contentUri: String,
    val displayName: String? = null,
    val dateTakenMillis: Long? = null,
    val dateAddedSeconds: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val mimeType: String? = null,
    val durationMillis: Long? = null
)

/**
 * Android-version-aware MediaStore permission state.
 */
enum class MediaPermissionGrantState {
    FULL_GRANTED,
    SELECTIVE_USER_SELECTED_GRANTED,
    DENIED,
    UNSUPPORTED
}

/**
 * Outcome of decoding a real thumbnail from a content URI.
 */
sealed class ThumbnailDecodeOutcome {
    data class Available(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int
    ) : ThumbnailDecodeOutcome()

    data class PreviewUnavailable(
        val reason: String = "Preview unavailable"
    ) : ThumbnailDecodeOutcome()
}

/**
 * Outcome of opening a content URI in the Fullscreen Photo Viewer.
 */
sealed class FullscreenPhotoLoadOutcome {
    data class Available(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int
    ) : FullscreenPhotoLoadOutcome()

    data class InaccessibleOrRevoked(
        val message: String
    ) : FullscreenPhotoLoadOutcome()

    data class DecodeError(
        val message: String
    ) : FullscreenPhotoLoadOutcome()
}

/**
 * Abstraction over Android media APIs used by [PhotoAccessManager].
 * Production code uses [AndroidContentResolverPhotoDataSource] exclusively.
 */
interface PhotoMediaDataSource {
    val sdkInt: Int
    fun isPhotoPickerSupported(): Boolean
    fun checkMediaPermissionState(): MediaPermissionGrantState
    fun queryMediaStoreImages(maxItems: Int): List<RawMediaRecord>
    fun isUriAccessible(uriString: String): Boolean
    fun tryTakePersistableReadPermission(uriString: String): UriPersistenceState
    fun queryUriMetadata(uriString: String): RawMediaRecord?
    fun decodeThumbnail(uriString: String, targetSizePx: Int, lowRamMode: Boolean): ThumbnailDecodeOutcome
    fun decodeFullscreenImage(uriString: String, maxDimensionPx: Int, lowRamMode: Boolean): FullscreenPhotoLoadOutcome
    fun loadPersistedUriStrings(): Set<String>
    fun savePersistedUriStrings(uris: Set<String>)
}

/**
 * Production [PhotoMediaDataSource] backed by real Android [ContentResolver],
 * [MediaStore.Images], and [ActivityResultContracts.PickVisualMedia].
 *
 * Strictly enforces:
 * - Zero fake URIs, filenames, dates, dimensions, or thumbnails.
 * - No querying of sensitive EXIF GPS coordinates or raw filesystem paths.
 * - Persistable URI permissions are stored ONLY when [ContentResolver.takePersistableUriPermission]
 *   and [ContentResolver.getPersistedUriPermissions] confirm the grant.
 */
class AndroidContentResolverPhotoDataSource(
    context: Context
) : PhotoMediaDataSource {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver? = runCatching { appContext.contentResolver }.getOrNull()
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("nova_photo_access_prefs", Context.MODE_PRIVATE)
    }

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun isPhotoPickerSupported(): Boolean {
        return runCatching {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(appContext)) {
                true
            } else {
                val contract = ActivityResultContracts.PickVisualMedia()
                val intent = contract.createIntent(
                    appContext,
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                intent.resolveActivity(appContext.packageManager) != null
            }
        }.getOrDefault(false)
    }

    override fun checkMediaPermissionState(): MediaPermissionGrantState {
        if (contentResolver == null) {
            return MediaPermissionGrantState.UNSUPPORTED
        }
        val currentSdk = sdkInt
        return when {
            currentSdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val fullGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                if (fullGranted) {
                    MediaPermissionGrantState.FULL_GRANTED
                } else {
                    val selectiveGranted = ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
                    if (selectiveGranted) {
                        MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED
                    } else {
                        MediaPermissionGrantState.DENIED
                    }
                }
            }
            currentSdk >= Build.VERSION_CODES.TIRAMISU -> {
                val fullGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                if (fullGranted) {
                    MediaPermissionGrantState.FULL_GRANTED
                } else {
                    MediaPermissionGrantState.DENIED
                }
            }
            else -> {
                val legacyGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (legacyGranted) {
                    MediaPermissionGrantState.FULL_GRANTED
                } else {
                    MediaPermissionGrantState.DENIED
                }
            }
        }
    }

    override fun queryMediaStoreImages(maxItems: Int): List<RawMediaRecord> {
        val resolver = contentResolver ?: throw IllegalStateException("ContentResolver unavailable")
        val collection: Uri = if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DURATION
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val results = mutableListOf<RawMediaRecord>()
        val safeLimit = maxItems.coerceIn(1, 48)

        resolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val durationCol = cursor.getColumnIndex(MediaStore.Images.Media.DURATION)

            while (cursor.moveToNext() && results.size < safeLimit) {
                if (idCol < 0 || cursor.isNull(idCol)) continue
                val id = cursor.getLong(idCol)
                if (id <= 0L) continue

                val itemUri = ContentUris.withAppendedId(collection, id).toString()
                val displayName = if (nameCol >= 0 && !cursor.isNull(nameCol)) {
                    cursor.getString(nameCol)?.trim()?.takeIf { it.isNotEmpty() }
                } else null

                val dateAddedSec = if (dateAddedCol >= 0 && !cursor.isNull(dateAddedCol)) {
                    cursor.getLong(dateAddedCol).takeIf { it > 0L }
                } else null

                val dateTakenMs = if (dateTakenCol >= 0 && !cursor.isNull(dateTakenCol)) {
                    cursor.getLong(dateTakenCol).takeIf { it > 0L }
                } else null

                val width = if (widthCol >= 0 && !cursor.isNull(widthCol)) {
                    cursor.getInt(widthCol).takeIf { it > 0 }
                } else null

                val height = if (heightCol >= 0 && !cursor.isNull(heightCol)) {
                    cursor.getInt(heightCol).takeIf { it > 0 }
                } else null

                val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) {
                    cursor.getString(mimeCol)?.trim()?.takeIf { it.isNotEmpty() }
                } else null

                val duration = if (durationCol >= 0 && !cursor.isNull(durationCol)) {
                    cursor.getLong(durationCol).takeIf { it > 0L }
                } else null

                results.add(
                    RawMediaRecord(
                        contentUri = itemUri,
                        displayName = displayName,
                        dateTakenMillis = dateTakenMs,
                        dateAddedSeconds = dateAddedSec,
                        widthPx = width,
                        heightPx = height,
                        mimeType = mime,
                        durationMillis = duration
                    )
                )
            }
        } ?: throw IllegalStateException("ContentResolver returned null cursor for MediaStore.Images")

        return results
    }

    override fun isUriAccessible(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val resolver = contentResolver ?: return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false

        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.fileDescriptor != null
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun tryTakePersistableReadPermission(uriString: String): UriPersistenceState {
        if (!isUriAccessible(uriString)) {
            return UriPersistenceState.ACCESS_REVOKED_OR_EXPIRED
        }
        val resolver = contentResolver ?: return UriPersistenceState.SESSION_SCOPED_ONLY
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return UriPersistenceState.ACCESS_REVOKED_OR_EXPIRED

        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val confirmedInOs = resolver.persistedUriPermissions.any { perm ->
                perm.uri == uri && perm.isReadPermission
            }
            if (confirmedInOs) {
                UriPersistenceState.PERSISTABLE_GRANTED
            } else {
                UriPersistenceState.SESSION_SCOPED_ONLY
            }
        } catch (_: UnsupportedOperationException) {
            UriPersistenceState.PERSISTENCE_NOT_SUPPORTED_BY_PROVIDER
        } catch (_: SecurityException) {
            // Provider did not offer FLAG_GRANT_PERSISTABLE_URI_PERMISSION; keep session-scoped only
            UriPersistenceState.SESSION_SCOPED_ONLY
        } catch (_: IllegalArgumentException) {
            UriPersistenceState.SESSION_SCOPED_ONLY
        }
    }

    override fun queryUriMetadata(uriString: String): RawMediaRecord? {
        if (!isUriAccessible(uriString)) return null
        val resolver = contentResolver ?: return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null

        var displayName: String? = null
        var dateTakenMs: Long? = null
        var dateAddedSec: Long? = null
        var widthPx: Int? = null
        var heightPx: Int? = null
        var mimeType: String? = runCatching { resolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        var durationMs: Long? = null

        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?: cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }
                    }

                    val takenIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (takenIdx >= 0 && !cursor.isNull(takenIdx)) {
                        dateTakenMs = cursor.getLong(takenIdx).takeIf { it > 0L }
                    }

                    val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    if (addedIdx >= 0 && !cursor.isNull(addedIdx)) {
                        dateAddedSec = cursor.getLong(addedIdx).takeIf { it > 0L }
                    }

                    val wIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    if (wIdx >= 0 && !cursor.isNull(wIdx)) {
                        widthPx = cursor.getInt(wIdx).takeIf { it > 0 }
                    }

                    val hIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    if (hIdx >= 0 && !cursor.isNull(hIdx)) {
                        heightPx = cursor.getInt(hIdx).takeIf { it > 0 }
                    }

                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                        val m = cursor.getString(mimeIdx)?.trim()?.takeIf { it.isNotEmpty() }
                        if (m != null) mimeType = m
                    }

                    val durIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                    if (durIdx >= 0 && !cursor.isNull(durIdx)) {
                        durationMs = cursor.getLong(durIdx).takeIf { it > 0L }
                    }
                }
            }
        }

        // If the provider did not report image dimensions in the cursor, inspect header bounds without allocating pixels
        if (widthPx == null || heightPx == null) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    widthPx = opts.outWidth
                    heightPx = opts.outHeight
                }
                if (mimeType == null && !opts.outMimeType.isNullOrBlank()) {
                    mimeType = opts.outMimeType
                }
            }
        }

        return RawMediaRecord(
            contentUri = uriString,
            displayName = displayName,
            dateTakenMillis = dateTakenMs,
            dateAddedSeconds = dateAddedSec,
            widthPx = widthPx,
            heightPx = heightPx,
            mimeType = mimeType,
            durationMillis = durationMs
        )
    }

    override fun decodeThumbnail(
        uriString: String,
        targetSizePx: Int,
        lowRamMode: Boolean
    ): ThumbnailDecodeOutcome {
        val resolver = contentResolver ?: return ThumbnailDecodeOutcome.PreviewUnavailable("ContentResolver unavailable")
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return ThumbnailDecodeOutcome.PreviewUnavailable("Invalid content URI")
        val safeTarget = targetSizePx.coerceIn(96, 320)

        // On Android 10+ (API 29+), try ContentResolver.loadThumbnail first when not forcing RGB_565 low-RAM decode
        if (sdkInt >= Build.VERSION_CODES.Q && !lowRamMode) {
            runCatching {
                val thumb = resolver.loadThumbnail(uri, Size(safeTarget, safeTarget), null)
                if (thumb.width > 0 && thumb.height > 0) {
                    return ThumbnailDecodeOutcome.Available(
                        bitmap = thumb,
                        widthPx = thumb.width,
                        heightPx = thumb.height
                    )
                }
            }
        }

        // Two-pass sampled stream decode suitable for 2–4 GB RAM / Android Go devices
        return decodeDownsampledStream(
            resolver = resolver,
            uri = uri,
            maxDimensionPx = safeTarget,
            lowRamMode = lowRamMode
        ).let { bmp ->
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                ThumbnailDecodeOutcome.Available(bmp, bmp.width, bmp.height)
            } else {
                ThumbnailDecodeOutcome.PreviewUnavailable("Preview unavailable")
            }
        }
    }

    override fun decodeFullscreenImage(
        uriString: String,
        maxDimensionPx: Int,
        lowRamMode: Boolean
    ): FullscreenPhotoLoadOutcome {
        if (!isUriAccessible(uriString)) {
            return FullscreenPhotoLoadOutcome.InaccessibleOrRevoked(
                "This photo URI is no longer accessible or permission was revoked."
            )
        }
        val resolver = contentResolver
            ?: return FullscreenPhotoLoadOutcome.DecodeError("ContentResolver is unavailable.")
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return FullscreenPhotoLoadOutcome.InaccessibleOrRevoked("Invalid content URI.")

        val safeMaxDim = if (lowRamMode) {
            maxDimensionPx.coerceIn(512, 1024)
        } else {
            maxDimensionPx.coerceIn(720, 1600)
        }

        return try {
            val bmp = decodeDownsampledStream(
                resolver = resolver,
                uri = uri,
                maxDimensionPx = safeMaxDim,
                lowRamMode = lowRamMode
            )
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                FullscreenPhotoLoadOutcome.Available(
                    bitmap = bmp,
                    widthPx = bmp.width,
                    heightPx = bmp.height
                )
            } else {
                FullscreenPhotoLoadOutcome.DecodeError("Unable to decode image from content URI.")
            }
        } catch (e: SecurityException) {
            FullscreenPhotoLoadOutcome.InaccessibleOrRevoked(
                "Permission to read this photo URI was revoked: ${e.message ?: "SecurityException"}"
            )
        } catch (e: Exception) {
            FullscreenPhotoLoadOutcome.DecodeError(
                "Could not open photo URI: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun decodeDownsampledStream(
        resolver: ContentResolver,
        uri: Uri,
        maxDimensionPx: Int,
        lowRamMode: Boolean
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                return null
            }

            val sampleSize = calculateInSampleSize(
                rawWidth = boundsOpts.outWidth,
                rawHeight = boundsOpts.outHeight,
                maxDimensionPx = maxDimensionPx
            )

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (lowRamMode) {
                    Bitmap.Config.RGB_565
                } else {
                    Bitmap.Config.ARGB_8888
                }
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun loadPersistedUriStrings(): Set<String> {
        return runCatching {
            prefs.getStringSet(KEY_PERSISTED_PHOTO_URIS, emptySet())?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    override fun savePersistedUriStrings(uris: Set<String>) {
        runCatching {
            prefs.edit().putStringSet(KEY_PERSISTED_PHOTO_URIS, uris).apply()
        }
    }

    companion object {
        private const val KEY_PERSISTED_PHOTO_URIS = "persisted_picker_photo_uris"

        fun calculateInSampleSize(
            rawWidth: Int,
            rawHeight: Int,
            maxDimensionPx: Int
        ): Int {
            var inSampleSize = 1
            if (rawHeight > maxDimensionPx || rawWidth > maxDimensionPx) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= maxDimensionPx ||
                    (halfWidth / inSampleSize) >= maxDimensionPx
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }
}

/**
 * M5 — Real Photo & Media Access Manager.
 *
 * Supports two strictly distinct Android media access paths:
 * 1. Android Photo Picker (`PickVisualMedia` / `PickMultipleVisualMedia`)
 * 2. Direct `MediaStore.Images` query when the appropriate Android version permission is granted
 *
 * Rules enforced:
 * - 0% mock/demo/fake URIs, filenames, dates, dimensions, or thumbnails.
 * - Only persists a selected URI when Android actually grants persistable URI permission.
 * - Non-persistable Photo Picker URIs are kept strictly session-scoped in memory.
 * - Low-RAM aware: limits workspace item counts, concurrent thumbnail decoding, and bitmap sizes.
 */
class PhotoAccessManager(
    private val dataSource: PhotoMediaDataSource
) {
    constructor(context: Context) : this(AndroidContentResolverPhotoDataSource(context))

    // Session-only Photo Picker items (never persisted to SharedPreferences)
    private val sessionPickedItems = mutableListOf<NovaPhotoItem>()

    // Persisted Photo Picker items (only URIs where OS verified takePersistableUriPermission)
    private val persistedPickedItems = mutableListOf<NovaPhotoItem>()

    // Concurrency limiter for thumbnail decoding on 2–4 GB RAM devices
    private val decodeSemaphore = Semaphore(permits = 2)

    // Bounded in-memory thumbnail cache (capped at 16 thumbnails in memory)
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 16
        }
    }

    init {
        restoreVerifiedPersistedPickerUris()
    }

    /**
     * Returns the exact Android runtime permissions required to query [MediaStore.Images]
     * on the current SDK version. Never requests `READ_EXTERNAL_STORAGE` on API 33+.
     */
    fun getRequiredMediaStorePermissions(): List<String> {
        return requiredMediaStorePermissionsForSdk(dataSource.sdkInt)
    }

    fun isPhotoPickerSupported(): Boolean {
        return dataSource.isPhotoPickerSupported()
    }

    /**
     * Restores previously persisted Photo Picker URIs, verifying each one against
     * [PhotoMediaDataSource.isUriAccessible] and pruning any revoked/expired URIs.
     */
    private fun restoreVerifiedPersistedPickerUris() {
        val storedUris = dataSource.loadPersistedUriStrings()
        if (storedUris.isEmpty()) return

        val stillValidUris = mutableSetOf<String>()
        persistedPickedItems.clear()

        for (uriStr in storedUris) {
            if (dataSource.isUriAccessible(uriStr)) {
                val raw = dataSource.queryUriMetadata(uriStr) ?: RawMediaRecord(contentUri = uriStr)
                persistedPickedItems.add(
                    mapRawRecordToPhotoItem(
                        raw = raw,
                        source = PhotoEntrySource.PHOTO_PICKER_PERSISTED,
                        persistenceState = UriPersistenceState.PERSISTABLE_GRANTED
                    )
                )
                stillValidUris.add(uriStr)
            }
        }

        if (stillValidUris.size != storedUris.size) {
            dataSource.savePersistedUriStrings(stillValidUris)
        }
    }

    /**
     * Queries photos for commands like "show my photos", "open my recent photos", "show gallery",
     * or "pick photos" (`preferPicker = true`).
     */
    fun queryPhotoWorkspace(
        preferPicker: Boolean = false,
        lowRamMode: Boolean = false
    ): PhotoAccessWorkspaceState {
        val pickerSupported = dataSource.isPhotoPickerSupported()
        val permissionState = dataSource.checkMediaPermissionState()

        if (permissionState == MediaPermissionGrantState.UNSUPPORTED && !pickerSupported) {
            return PhotoAccessWorkspaceState(
                accessMode = PhotoAccessMode.UNAVAILABLE,
                mediaStoreAccessState = MediaStoreQueryAccessState.QUERY_ERROR,
                isPhotoPickerSupported = false,
                statusMessage = "Photo and MediaStore capabilities are unavailable on this device."
            )
        }

        // If user explicitly asked to pick/select photos via Photo Picker
        if (preferPicker) {
            if (!pickerSupported) {
                return PhotoAccessWorkspaceState(
                    accessMode = PhotoAccessMode.UNAVAILABLE,
                    mediaStoreAccessState = toMediaStoreContractState(permissionState),
                    isPhotoPickerSupported = false,
                    statusMessage = "Android Photo Picker is not supported on this device."
                )
            }
            val activePicked = getVerifiedActivePickedItems()
            return if (activePicked.isNotEmpty()) {
                val allPersisted = activePicked.all {
                    it.uriPersistenceState == UriPersistenceState.PERSISTABLE_GRANTED
                }
                val mode = if (allPersisted) {
                    PhotoAccessMode.PICKED_URI_PERSISTED
                } else {
                    PhotoAccessMode.PICKED_URI_SESSION
                }
                PhotoAccessWorkspaceState(
                    pickerSelectedEntries = activePicked.map { it.toPickedPhotoUriEntry() },
                    mediaStoreAccessState = toMediaStoreContractState(permissionState),
                    mediaStoreEntries = emptyList(),
                    statusMessage = "Select photos using Android Photo Picker or view your ${activePicked.size} selected photo(s).",
                    accessMode = mode,
                    photos = activePicked,
                    isSelectiveMediaAccess = permissionState == MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED,
                    isPickerLaunchRequested = true,
                    isPhotoPickerSupported = true
                )
            } else {
                PhotoAccessWorkspaceState(
                    pickerSelectedEntries = emptyList(),
                    mediaStoreAccessState = toMediaStoreContractState(permissionState),
                    mediaStoreEntries = emptyList(),
                    statusMessage = "Use Android Photo Picker to select photos.",
                    accessMode = PhotoAccessMode.PERMISSION_REQUIRED,
                    photos = emptyList(),
                    isSelectiveMediaAccess = permissionState == MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED,
                    isPickerLaunchRequested = true,
                    isPhotoPickerSupported = true
                )
            }
        }

        // Standard "show my photos" / "show recent photos" path:
        // 1. Check MediaStore permission first
        if (permissionState == MediaPermissionGrantState.FULL_GRANTED ||
            permissionState == MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED
        ) {
            val maxItems = if (lowRamMode) MAX_ITEMS_LOW_RAM else MAX_ITEMS_STANDARD
            return try {
                val rawRecords = dataSource.queryMediaStoreImages(maxItems)
                val validRecords = rawRecords.filter { it.contentUri.startsWith("content://") }
                val photoItems = validRecords.map { raw ->
                    mapRawRecordToPhotoItem(
                        raw = raw,
                        source = PhotoEntrySource.MEDIASTORE_QUERY,
                        persistenceState = null
                    )
                }
                val mediaStoreEntries = validRecords.map { raw ->
                    MediaStorePhotoEntry(
                        contentUriString = raw.contentUri,
                        displayName = raw.displayName ?: "Unavailable",
                        dateAddedSeconds = raw.dateAddedSeconds ?: -1L,
                        widthPx = raw.widthPx ?: -1,
                        heightPx = raw.heightPx ?: -1
                    )
                }
                val isSelective = permissionState == MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED
                val activePicked = getVerifiedActivePickedItems()

                if (photoItems.isNotEmpty()) {
                    PhotoAccessWorkspaceState(
                        pickerSelectedEntries = activePicked.map { it.toPickedPhotoUriEntry() },
                        mediaStoreAccessState = MediaStoreQueryAccessState.READ_MEDIA_PERMISSION_GRANTED,
                        mediaStoreEntries = mediaStoreEntries,
                        statusMessage = if (isSelective) {
                            "Showing ${photoItems.size} photo(s) allowed via Android Selected Photos access."
                        } else {
                            "Showing ${photoItems.size} recent photo(s) from MediaStore."
                        },
                        accessMode = PhotoAccessMode.MEDIASTORE_ACCESS,
                        photos = photoItems,
                        isSelectiveMediaAccess = isSelective,
                        isPickerLaunchRequested = false,
                        isPhotoPickerSupported = pickerSupported
                    )
                } else {
                    PhotoAccessWorkspaceState(
                        pickerSelectedEntries = activePicked.map { it.toPickedPhotoUriEntry() },
                        mediaStoreAccessState = MediaStoreQueryAccessState.QUERY_EMPTY,
                        mediaStoreEntries = emptyList(),
                        statusMessage = "No accessible photos were found in your device's media library.",
                        accessMode = PhotoAccessMode.EMPTY,
                        photos = emptyList(),
                        isSelectiveMediaAccess = isSelective,
                        isPickerLaunchRequested = false,
                        isPhotoPickerSupported = pickerSupported
                    )
                }
            } catch (e: SecurityException) {
                PhotoAccessWorkspaceState(
                    pickerSelectedEntries = emptyList(),
                    mediaStoreAccessState = MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
                    mediaStoreEntries = emptyList(),
                    statusMessage = "Photo access is required to read your recent photos.",
                    accessMode = PhotoAccessMode.PERMISSION_REQUIRED,
                    photos = emptyList(),
                    isPhotoPickerSupported = pickerSupported,
                    errorMessage = e.message
                )
            } catch (e: Exception) {
                PhotoAccessWorkspaceState(
                    pickerSelectedEntries = emptyList(),
                    mediaStoreAccessState = MediaStoreQueryAccessState.QUERY_ERROR,
                    mediaStoreEntries = emptyList(),
                    statusMessage = "Failed to query device photos: ${e.message ?: e.javaClass.simpleName}",
                    accessMode = PhotoAccessMode.ERROR,
                    photos = emptyList(),
                    isPhotoPickerSupported = pickerSupported,
                    errorMessage = e.message ?: "MediaStore query failed"
                )
            }
        }

        // 2. MediaStore permission is NOT granted -> strictly report PERMISSION_REQUIRED
        // (Unless the user has active verified Photo Picker selections in this session)
        val activePicked = getVerifiedActivePickedItems()
        if (activePicked.isNotEmpty()) {
            val allPersisted = activePicked.all {
                it.uriPersistenceState == UriPersistenceState.PERSISTABLE_GRANTED
            }
            val mode = if (allPersisted) {
                PhotoAccessMode.PICKED_URI_PERSISTED
            } else {
                PhotoAccessMode.PICKED_URI_SESSION
            }
            return PhotoAccessWorkspaceState(
                pickerSelectedEntries = activePicked.map { it.toPickedPhotoUriEntry() },
                // Keep MediaStore state strictly separate — Picker selection NEVER implies MediaStore access
                mediaStoreAccessState = MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
                mediaStoreEntries = emptyList(),
                statusMessage = if (allPersisted) {
                    "Showing ${activePicked.size} photo(s) from persisted Photo Picker selection (MediaStore permission not granted)."
                } else {
                    "Showing ${activePicked.size} session-scoped photo(s) from Android Photo Picker."
                },
                accessMode = mode,
                photos = activePicked,
                isSelectiveMediaAccess = false,
                isPickerLaunchRequested = false,
                isPhotoPickerSupported = pickerSupported
            )
        }

        return PhotoAccessWorkspaceState(
            pickerSelectedEntries = emptyList(),
            mediaStoreAccessState = MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
            mediaStoreEntries = emptyList(),
            statusMessage = "Photo access is required. Grant photo permission or select photos using Android Photo Picker.",
            accessMode = PhotoAccessMode.PERMISSION_REQUIRED,
            photos = emptyList(),
            isSelectiveMediaAccess = false,
            isPickerLaunchRequested = false,
            isPhotoPickerSupported = pickerSupported
        )
    }

    /**
     * Ingests real content URIs selected by the user from the Android Photo Picker.
     *
     * Rules enforced:
     * - Rejects blank or non-`content://` URIs.
     * - Verifies each URI is actually readable via [PhotoMediaDataSource.isUriAccessible].
     * - Attempts [PhotoMediaDataSource.tryTakePersistableReadPermission] and persists ONLY
     *   those URIs where Android genuinely granted persistable permission.
     * - Keeps non-persistable URIs strictly session-scoped in memory.
     * - Never upgrades [MediaStoreQueryAccessState] to `READ_MEDIA_PERMISSION_GRANTED` based on picker URIs.
     */
    fun ingestPickedUris(
        selectedUriStrings: List<String>,
        lowRamMode: Boolean = false
    ): PhotoAccessWorkspaceState {
        val pickerSupported = dataSource.isPhotoPickerSupported()
        val mediaPermissionState = dataSource.checkMediaPermissionState()
        val mediaStoreContractState = toMediaStoreContractState(mediaPermissionState)

        val maxAllowed = if (lowRamMode) MAX_ITEMS_LOW_RAM else MAX_ITEMS_STANDARD
        val cleanedInput = selectedUriStrings
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(maxAllowed)

        if (cleanedInput.isEmpty()) {
            // User dismissed the Photo Picker without selecting any photos
            val existingPicked = getVerifiedActivePickedItems()
            if (existingPicked.isNotEmpty()) {
                val allPersisted = existingPicked.all {
                    it.uriPersistenceState == UriPersistenceState.PERSISTABLE_GRANTED
                }
                return PhotoAccessWorkspaceState(
                    pickerSelectedEntries = existingPicked.map { it.toPickedPhotoUriEntry() },
                    mediaStoreAccessState = mediaStoreContractState,
                    mediaStoreEntries = emptyList(),
                    statusMessage = "No new photos selected. Showing ${existingPicked.size} previously selected photo(s).",
                    accessMode = if (allPersisted) PhotoAccessMode.PICKED_URI_PERSISTED else PhotoAccessMode.PICKED_URI_SESSION,
                    photos = existingPicked,
                    isPhotoPickerSupported = pickerSupported
                )
            }
            return PhotoAccessWorkspaceState(
                pickerSelectedEntries = emptyList(),
                mediaStoreAccessState = mediaStoreContractState,
                mediaStoreEntries = emptyList(),
                statusMessage = "No photos were selected from the Android Photo Picker.",
                accessMode = if (mediaPermissionState == MediaPermissionGrantState.DENIED) {
                    PhotoAccessMode.PERMISSION_REQUIRED
                } else {
                    PhotoAccessMode.EMPTY
                },
                photos = emptyList(),
                isPhotoPickerSupported = pickerSupported
            )
        }

        val newlySessionScoped = mutableListOf<NovaPhotoItem>()
        val newlyPersisted = mutableListOf<NovaPhotoItem>()
        var inaccessibleCount = 0

        for (uriStr in cleanedInput) {
            if (!uriStr.startsWith("content://") || !dataSource.isUriAccessible(uriStr)) {
                inaccessibleCount++
                continue
            }

            val persistenceState = dataSource.tryTakePersistableReadPermission(uriStr)
            if (persistenceState == UriPersistenceState.ACCESS_REVOKED_OR_EXPIRED) {
                inaccessibleCount++
                continue
            }

            val rawMetadata = dataSource.queryUriMetadata(uriStr)
                ?: RawMediaRecord(contentUri = uriStr)

            if (persistenceState == UriPersistenceState.PERSISTABLE_GRANTED) {
                newlyPersisted.add(
                    mapRawRecordToPhotoItem(
                        raw = rawMetadata,
                        source = PhotoEntrySource.PHOTO_PICKER_PERSISTED,
                        persistenceState = UriPersistenceState.PERSISTABLE_GRANTED
                    )
                )
            } else {
                // SESSION_SCOPED_ONLY or PERSISTENCE_NOT_SUPPORTED_BY_PROVIDER
                newlySessionScoped.add(
                    mapRawRecordToPhotoItem(
                        raw = rawMetadata,
                        source = PhotoEntrySource.PHOTO_PICKER_SESSION,
                        persistenceState = persistenceState
                    )
                )
            }
        }

        // Update in-memory session and persisted collections
        if (newlySessionScoped.isNotEmpty() || newlyPersisted.isNotEmpty()) {
            sessionPickedItems.clear()
            sessionPickedItems.addAll(newlySessionScoped)

            // Merge newly persisted items with existing verified persisted items
            val mergedPersistedByUri = LinkedHashMap<String, NovaPhotoItem>()
            for (item in newlyPersisted) {
                mergedPersistedByUri[item.contentUri] = item
            }
            for (existing in persistedPickedItems) {
                if (!mergedPersistedByUri.containsKey(existing.contentUri) &&
                    dataSource.isUriAccessible(existing.contentUri) &&
                    mergedPersistedByUri.size < maxAllowed
                ) {
                    mergedPersistedByUri[existing.contentUri] = existing
                }
            }
            persistedPickedItems.clear()
            persistedPickedItems.addAll(mergedPersistedByUri.values)

            // Save ONLY verified persistable URIs to SharedPreferences
            dataSource.savePersistedUriStrings(
                persistedPickedItems.map { it.contentUri }.toSet()
            )
        }

        val combinedActive = (newlyPersisted + newlySessionScoped)
        if (combinedActive.isEmpty()) {
            return PhotoAccessWorkspaceState(
                pickerSelectedEntries = emptyList(),
                mediaStoreAccessState = mediaStoreContractState,
                mediaStoreEntries = emptyList(),
                statusMessage = "Selected photo URI(s) could not be opened or access was revoked.",
                accessMode = PhotoAccessMode.ERROR,
                photos = emptyList(),
                isPhotoPickerSupported = pickerSupported,
                errorMessage = "Selected URI is inaccessible or permission was revoked ($inaccessibleCount failed)."
            )
        }

        val allPersisted = combinedActive.all {
            it.uriPersistenceState == UriPersistenceState.PERSISTABLE_GRANTED
        }
        val mode = if (allPersisted) {
            PhotoAccessMode.PICKED_URI_PERSISTED
        } else {
            PhotoAccessMode.PICKED_URI_SESSION
        }

        val statusMsg = buildString {
            append("Selected ${combinedActive.size} photo(s) via Android Photo Picker ")
            if (allPersisted) {
                append("(persistable read access granted).")
            } else {
                append("(session-scoped access only; not stored permanently).")
            }
            if (inaccessibleCount > 0) {
                append(" $inaccessibleCount inaccessible URI(s) were skipped.")
            }
        }

        return PhotoAccessWorkspaceState(
            pickerSelectedEntries = combinedActive.map { it.toPickedPhotoUriEntry() },
            mediaStoreAccessState = mediaStoreContractState,
            mediaStoreEntries = emptyList(),
            statusMessage = statusMsg,
            accessMode = mode,
            photos = combinedActive,
            isSelectiveMediaAccess = mediaPermissionState == MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED,
            isPickerLaunchRequested = false,
            isPhotoPickerSupported = pickerSupported
        )
    }

    /**
     * Verifies whether a specific photo URI is still accessible right now.
     * If a persisted or session URI has been revoked or deleted, prunes it and returns false.
     */
    fun verifyAndPruneIfRevoked(uriString: String): Boolean {
        val accessible = dataSource.isUriAccessible(uriString)
        if (!accessible) {
            sessionPickedItems.removeAll { it.contentUri == uriString }
            val removedPersisted = persistedPickedItems.removeAll { it.contentUri == uriString }
            if (removedPersisted) {
                dataSource.savePersistedUriStrings(
                    persistedPickedItems.map { it.contentUri }.toSet()
                )
            }
            thumbnailCache.remove(uriString)
        }
        return accessible
    }

    /**
     * Loads a real thumbnail for [uriString] with bounded concurrency and memory caching.
     * Never returns a synthetic or placeholder image; returns [ThumbnailDecodeOutcome.PreviewUnavailable]
     * if the real image cannot be decoded.
     */
    suspend fun loadThumbnail(
        uriString: String,
        lowRamMode: Boolean
    ): ThumbnailDecodeOutcome = withContext(Dispatchers.IO) {
        if (uriString.isBlank() || !uriString.startsWith("content://")) {
            return@withContext ThumbnailDecodeOutcome.PreviewUnavailable("Invalid content URI")
        }

        val cached = synchronized(thumbnailCache) { thumbnailCache.get(uriString) }
        if (cached != null && !cached.isRecycled) {
            return@withContext ThumbnailDecodeOutcome.Available(
                bitmap = cached,
                widthPx = cached.width,
                heightPx = cached.height
            )
        }

        val targetPx = if (lowRamMode) THUMBNAIL_PX_LOW_RAM else THUMBNAIL_PX_STANDARD
        decodeSemaphore.withPermit {
            val recheck = synchronized(thumbnailCache) { thumbnailCache.get(uriString) }
            if (recheck != null && !recheck.isRecycled) {
                return@withPermit ThumbnailDecodeOutcome.Available(
                    bitmap = recheck,
                    widthPx = recheck.width,
                    heightPx = recheck.height
                )
            }

            if (!verifyAndPruneIfRevoked(uriString)) {
                return@withPermit ThumbnailDecodeOutcome.PreviewUnavailable("URI inaccessible or revoked")
            }

            val outcome = dataSource.decodeThumbnail(
                uriString = uriString,
                targetSizePx = targetPx,
                lowRamMode = lowRamMode
            )
            if (outcome is ThumbnailDecodeOutcome.Available) {
                synchronized(thumbnailCache) {
                    thumbnailCache.put(uriString, outcome.bitmap)
                }
            }
            outcome
        }
    }

    /**
     * Loads a display-sized bitmap for the Fullscreen Photo Viewer directly from the content URI
     * without copying files, handling revoked or inaccessible URIs truthfully.
     */
    suspend fun loadFullscreenPhoto(
        uriString: String,
        lowRamMode: Boolean
    ): FullscreenPhotoLoadOutcome = withContext(Dispatchers.IO) {
        if (!verifyAndPruneIfRevoked(uriString)) {
            return@withContext FullscreenPhotoLoadOutcome.InaccessibleOrRevoked(
                "This photo URI is no longer accessible or permission was revoked."
            )
        }
        val maxDim = if (lowRamMode) FULLSCREEN_MAX_PX_LOW_RAM else FULLSCREEN_MAX_PX_STANDARD
        dataSource.decodeFullscreenImage(
            uriString = uriString,
            maxDimensionPx = maxDim,
            lowRamMode = lowRamMode
        )
    }

    fun clearThumbnailCache() {
        synchronized(thumbnailCache) {
            thumbnailCache.clear()
        }
    }

    private fun getVerifiedActivePickedItems(): List<NovaPhotoItem> {
        val validSession = sessionPickedItems.filter { dataSource.isUriAccessible(it.contentUri) }
        if (validSession.size != sessionPickedItems.size) {
            sessionPickedItems.clear()
            sessionPickedItems.addAll(validSession)
        }

        val validPersisted = persistedPickedItems.filter { dataSource.isUriAccessible(it.contentUri) }
        if (validPersisted.size != persistedPickedItems.size) {
            persistedPickedItems.clear()
            persistedPickedItems.addAll(validPersisted)
            dataSource.savePersistedUriStrings(validPersisted.map { it.contentUri }.toSet())
        }

        val combined = LinkedHashMap<String, NovaPhotoItem>()
        for (item in validSession) {
            combined[item.contentUri] = item
        }
        for (item in validPersisted) {
            if (!combined.containsKey(item.contentUri)) {
                combined[item.contentUri] = item
            }
        }
        return combined.values.toList()
    }

    private fun toMediaStoreContractState(grant: MediaPermissionGrantState): MediaStoreQueryAccessState {
        return when (grant) {
            MediaPermissionGrantState.FULL_GRANTED,
            MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED ->
                MediaStoreQueryAccessState.READ_MEDIA_PERMISSION_GRANTED
            MediaPermissionGrantState.DENIED ->
                MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION
            MediaPermissionGrantState.UNSUPPORTED ->
                MediaStoreQueryAccessState.QUERY_ERROR
        }
    }

    private fun NovaPhotoItem.toPickedPhotoUriEntry(): PickedPhotoUriEntry {
        return PickedPhotoUriEntry(
            uriString = contentUri,
            displayName = displayName.valueOrNull() ?: "Unavailable",
            mimeType = mimeType.valueOrNull() ?: "Unavailable",
            dateTakenOrAddedMs = dateTakenOrAddedMillis.valueOrNull(),
            persistenceState = uriPersistenceState ?: UriPersistenceState.SESSION_SCOPED_ONLY
        )
    }

    companion object {
        const val MAX_ITEMS_STANDARD = 24
        const val MAX_ITEMS_LOW_RAM = 12
        const val THUMBNAIL_PX_STANDARD = 220
        const val THUMBNAIL_PX_LOW_RAM = 144
        const val FULLSCREEN_MAX_PX_STANDARD = 1440
        const val FULLSCREEN_MAX_PX_LOW_RAM = 960

        /**
         * Pure function returning the exact Android runtime permissions required for
         * [MediaStore.Images] queries on a given [sdkInt].
         */
        fun requiredMediaStorePermissionsForSdk(sdkInt: Int): List<String> {
            return when {
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
                    Manifest.permission.READ_MEDIA_IMAGES
                )
                else -> listOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        }

        /**
         * Maps a [RawMediaRecord] into a [NovaPhotoItem] with explicit [DeviceFieldState]
         * wrappers for every nullable metadata field.
         * Never substitutes fake filenames, fake dates, or fake dimensions.
         */
        fun mapRawRecordToPhotoItem(
            raw: RawMediaRecord,
            source: PhotoEntrySource,
            persistenceState: UriPersistenceState?
        ): NovaPhotoItem {
            val nameState: DeviceFieldState<String> = if (!raw.displayName.isNullOrBlank()) {
                DeviceFieldState.Available(raw.displayName.trim())
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val resolvedTimestampMs: Long? = when {
                raw.dateTakenMillis != null && raw.dateTakenMillis > 0L -> raw.dateTakenMillis
                raw.dateAddedSeconds != null && raw.dateAddedSeconds > 0L -> raw.dateAddedSeconds * 1000L
                else -> null
            }
            val dateState: DeviceFieldState<Long> = if (resolvedTimestampMs != null) {
                DeviceFieldState.Available(resolvedTimestampMs)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val widthState: DeviceFieldState<Int> = if (raw.widthPx != null && raw.widthPx > 0) {
                DeviceFieldState.Available(raw.widthPx)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val heightState: DeviceFieldState<Int> = if (raw.heightPx != null && raw.heightPx > 0) {
                DeviceFieldState.Available(raw.heightPx)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val mimeState: DeviceFieldState<String> = if (!raw.mimeType.isNullOrBlank()) {
                DeviceFieldState.Available(raw.mimeType.trim())
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val durationState: DeviceFieldState<Long> = if (raw.durationMillis != null && raw.durationMillis > 0L) {
                DeviceFieldState.Available(raw.durationMillis)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            return NovaPhotoItem(
                contentUri = raw.contentUri,
                displayName = nameState,
                dateTakenOrAddedMillis = dateState,
                widthPx = widthState,
                heightPx = heightState,
                mimeType = mimeState,
                durationMillis = durationState,
                source = source,
                uriPersistenceState = persistenceState
            )
        }
    }
}
