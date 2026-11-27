package com.github.libretube.api.obj

import android.os.Parcelable
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlin.io.path.Path

@Serializable
@Parcelize
data class PipedStream(
    var url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val videoOnly: Boolean? = null,
    val bitrate: Int? = null,
    val initStart: Int? = null,
    val initEnd: Int? = null,
    val indexStart: Int? = null,
    val indexEnd: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val durationMs: Long? = null,
    val audioTrackName: String? = null,
    val audioTrackId: String? = null,
    val contentLength: Long = -1,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null,
    val itag: Int? = null,
    val lastModified: Long? = null,
    val isDrc: Boolean? = null,
    val xtags: String? = null,
): Parcelable {
    private fun getQualityString(videoId: String, fileType: FileType): String {
        return "${videoId}_${quality?.replace(" ", "_")}_$format." + getFileExtension(fileType)
    }

    private fun getFileExtension(fileType: FileType): String? {
        // audio only MP4 files are commonly known as M4A files, the MIME type would result in .mp4
        // WebM audio files are converted to .opus files once the download is finished, see
        // [com.github.libretube.helpers.AudioRemuxHelper]
        if (fileType == FileType.AUDIO && mimeType == AUDIO_MP4_MIME_TYPE) return M4A_EXTENSION

        return mimeType?.split("/")?.last()
    }

    fun toDownloadItem(fileType: FileType, videoId: String) = DownloadItem(
        type = fileType,
        videoId = videoId,
        fileName = getQualityString(videoId, fileType),
        path = Path(""),
        format = format,
        quality = quality,
        language = audioTrackLocale,
        downloadSize = contentLength
    )

    companion object {
        private const val AUDIO_MP4_MIME_TYPE = "audio/mp4"
        private const val M4A_EXTENSION = "m4a"
    }
}
