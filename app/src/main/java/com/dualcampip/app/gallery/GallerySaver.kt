package com.dualcampip.app.gallery

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/**
 * Writes a finished MP4 into the device gallery via MediaStore.
 * The file stays on device; nothing here uploads it.
 */
class GallerySaver(
    private val resolver: ContentResolver,
) {
    fun createPending(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/DualCamPiP",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return resolver.insert(collection(), values)
            ?: throw IllegalStateException("mediastore insert returned null")
    }

    fun open(uri: Uri): ParcelFileDescriptor {
        return resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("mediastore fd was null")
    }

    fun publish(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    fun abandon(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun collection(): Uri {
        return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
