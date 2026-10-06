package app.imagesaver

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves into the public DCIM/ImageSaver folder via MediaStore (no broad storage permission on API 29+). */
class MediaStoreImageStorage(context: Context) : ImageStorage {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun save(fileName: String, jpeg: ByteArray): StoredImage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(fileName, jpeg) else saveLegacy(fileName, jpeg)

    private fun saveScoped(fileName: String, jpeg: ByteArray): StoredImage {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(fileName, "$RELATIVE_PATH/"),
            null,
        )?.use { if (it.moveToFirst()) throw NameExistsException(fileName) }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create entry in MediaStore")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $uri")
            out.use { it.write(jpeg) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
        return StoredImage(queryName(uri) ?: fileName, jpeg.size.toLong(), uri.toString())
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(fileName: String, jpeg: ByteArray): StoredImage {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), SUBFOLDER)
        val stored = FileImageStorage(dir).save(fileName, jpeg)
        val file = File(dir, stored.fileName)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATA, file.absolutePath)
            put(MediaStore.Images.Media.SIZE, file.length())
        }
        val uri: Uri? = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        }
        return StoredImage(file.name, file.length(), uri?.toString())
    }

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        const val SUBFOLDER = "ImageSaver"
        const val RELATIVE_PATH = "DCIM/$SUBFOLDER"
    }
}
