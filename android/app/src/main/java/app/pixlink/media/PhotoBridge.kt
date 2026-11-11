package app.pixlink.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import app.pixlink.link.Envelope
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The photo gallery: newest first, with thumbnails on request. */
class PhotoBridge(private val context: Context) {

    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun list(request: Envelope, offset: Int, limit: Int): Envelope {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_TAKEN))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
        )
        return request.reply("photos.list") {
            putJsonArray("photos") {
                context.contentResolver.query(collection, projection, args, null)?.use { c ->
                    while (c.moveToNext()) addJsonObject {
                        put("id", c.getLong(0))
                        put("date", c.getLong(1))
                        put("width", c.getInt(2))
                        put("height", c.getInt(3))
                        put("name", c.getString(4))
                        put("size", c.getLong(5))
                    }
                }
            }
        }
    }

    fun thumb(request: Envelope, id: Long, size: Int): Envelope {
        val uri = ContentUris.withAppendedId(collection, id)
        val bmp = context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return request.reply("photos.thumb") {
            put("id", id)
            put("jpegBase64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    fun open(id: Long): Pair<String, InputStream>? {
        val uri = ContentUris.withAppendedId(collection, id)
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "photo_$id.jpg"
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return name to stream
    }
}
