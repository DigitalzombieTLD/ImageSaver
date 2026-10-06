package app.imagesaver

import java.io.File
import java.io.IOException

/** A saved image; [uri] is an openable content URI when available. */
data class StoredImage(val fileName: String, val sizeBytes: Long, val uri: String?)

class NameExistsException(name: String) : IOException("File already exists: $name")

interface ImageStorage {
    /** Stores [jpeg] under [fileName]; throws [NameExistsException] if the name is taken, [IOException] otherwise. */
    fun save(fileName: String, jpeg: ByteArray): StoredImage
}

/** Plain-file storage (used for tests and as a platform-independent implementation). */
class FileImageStorage(private val directory: File) : ImageStorage {
    override fun save(fileName: String, jpeg: ByteArray): StoredImage {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
        val file = File(directory, fileName)
        if (!file.createNewFile()) throw NameExistsException(fileName) // atomic
        try {
            file.writeBytes(jpeg)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return StoredImage(file.name, file.length(), null)
    }
}
