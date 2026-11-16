package com.github.libretube.helpers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.dialogs.DownloadDialog
import com.github.libretube.ui.dialogs.DownloadPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isWritable

object DownloadHelper {
    const val VIDEO_DIR = "video"
    const val AUDIO_DIR = "audio"
    const val SUBTITLE_DIR = "subtitle"
    const val THUMBNAIL_DIR = "thumbnail"
    const val PLAYLIST_THUMBNAIL_DIR = "playlist_thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 8L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val VIDEO_MIMETYPE = "video/*"
    private const val NO_MEDIA_FILE = ".nomedia"
    private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
        "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME_ID = "primary"

    /**
     * Default base directory for downloads: the app specific external storage directory,
     * or the internal files directory if no external storage is available.
     */
    fun getDefaultDownloadBaseDir(context: Context): File {
        return try {
            context.getExternalFilesDir(null)!!
        } catch (e: Exception) {
            context.filesDir
        }
    }

    /**
     * The custom base directory for downloads selected by the user, or null if the default
     * location should be used.
     */
    fun getCustomDownloadBaseDir(): File? {
        val customPath = PreferenceHelper.getString(PreferenceKeys.DOWNLOAD_LOCATION, "")
        return customPath.takeIf { it.isNotBlank() }?.let { File(it) }
    }

    /**
     * The base directory that is currently used for new downloads.
     */
    fun getDownloadBaseDir(context: Context): File {
        return getCustomDownloadBaseDir() ?: getDefaultDownloadBaseDir(context)
    }

    fun getDownloadDir(context: Context, path: String): Path {
        getCustomDownloadBaseDir()?.let { customDir ->
            try {
                val dir = (customDir.toPath() / path).createDirectories()
                if (path == THUMBNAIL_DIR || path == PLAYLIST_THUMBNAIL_DIR) {
                    // prevent thumbnails from showing up in the user's gallery
                    val noMediaFile = dir / NO_MEDIA_FILE
                    if (!noMediaFile.exists()) runCatching { noMediaFile.createFile() }
                }
                if (dir.isWritable()) return dir
            } catch (e: Exception) {
                Log.e(TAG(), "Custom download location not usable, falling back to default", e)
            }
        }

        return (getDefaultDownloadBaseDir(context).toPath() / path).createDirectories()
    }

    /**
     * Convert a tree [Uri] returned by [Intent.ACTION_OPEN_DOCUMENT_TREE] into an absolute
     * file system path. Only works for folders provided by the external storage provider
     * (internal shared storage and SD cards).
     *
     * @return the absolute path or null if the uri can't be mapped to a file system path
     */
    fun treeUriToFilePath(context: Context, treeUri: Uri): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_PROVIDER_AUTHORITY) return null

        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        val volumeId = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")

        val volumeRoot = if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } else {
            findVolumeRoot(context, volumeId) ?: return null
        }

        return if (relativePath.isEmpty()) {
            volumeRoot.absolutePath
        } else {
            File(volumeRoot, relativePath).absolutePath
        }
    }

    private fun findVolumeRoot(context: Context, volumeId: String): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService<StorageManager>() ?: return null
            storageManager.storageVolumes.forEach { volume ->
                if (volume.uuid.equals(volumeId, ignoreCase = true)) return volume.directory
            }
        }

        // fallback for older Android versions: removable volumes are mounted at /storage/<uuid>
        return File("/storage", volumeId).takeIf { it.exists() }
    }

    /**
     * Whether the app is allowed to write to arbitrary folders on the shared storage.
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        ContextCompat.startForegroundService(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            DownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, DownloadDialog::class.java.name)
        } else {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            val downloadPlaylistDialog = DownloadPlaylistDialog().apply {
                arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName,
                    IntentData.playlistType to playlistType
                )
            }
            downloadPlaylistDialog.show(fragmentManager, null)
        } else if (playlistType == PlaylistType.PUBLIC) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val playlistVideoIds = try {
                    PlaylistsHelper.getPlaylist(playlistId, PlaylistsHelper.getPlaylistType(playlistId))
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.unknown_error)
                    return@launch
                }.relatedStreams.mapNotNull { it.url?.toID() }.joinToString(",")

                val intent = Intent(Intent.ACTION_VIEW)
                    .setPackage(externalProviderPackageName)
                    .setDataAndType(
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch_videos?video_ids=${playlistVideoIds}".toUri(),
                        VIDEO_MIMETYPE
                    )

                withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    fun extractDownloadInfoText(context: Context, download: DownloadWithItems): List<String> {
        val downloadInfo = mutableListOf<String>()
        download.downloadItems.firstOrNull { it.type == FileType.VIDEO }?.let { videoItem ->
            downloadInfo.add(context.getString(R.string.video) + ": ${videoItem.format} ${videoItem.quality}")
        }
        download.downloadItems.firstOrNull { it.type == FileType.AUDIO }?.let { audioItem ->
            var infoString = ": ${audioItem.quality} ${audioItem.format})"
            if (audioItem.language != null) infoString += " ${audioItem.language}"
            downloadInfo.add(context.getString(R.string.audio) + infoString)
        }
        download.downloadItems.firstOrNull { it.type == FileType.SUBTITLE }?.let {
            downloadInfo.add(context.getString(R.string.captions) + ": ${it.language}")
        }
        return downloadInfo
    }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }
}
