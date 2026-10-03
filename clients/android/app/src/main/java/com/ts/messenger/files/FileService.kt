package com.ts.messenger.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.ts.messenger.net.FileRef
import com.ts.messenger.net.TsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Attachments: encrypts before upload, keeps only ciphertext on disk (no-backup directory) and
 * decrypts on demand. Plaintext never touches the file system: images are decoded from memory
 * and "save" streams straight into the destination the user picked.
 */
class FileService(context: Context, private val api: TsApi) {
    private val resolver = context.contentResolver
    private val dir = blobDir(context)

    class Picked(val name: String, val mime: String, val size: Long, val open: () -> InputStream)

    fun blob(id: String): File {
        require(ID.matches(id)) { "bad file id" }
        return File(dir, "$id.bin")
    }

    /** Reads what the user picked. Photos are re-encoded, which drops EXIF/GPS metadata. */
    suspend fun pick(uri: Uri): Picked = withContext(Dispatchers.IO) {
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        name = sanitizeName(name)
        if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") {
            val png = mime == "image/png"
            val clean = stripImage(uri, png)
            val outName = if (mime == "image/jpeg" || mime == "image/png") name
            else name.substringBeforeLast('.', name) + if (png) ".png" else ".jpg"
            return@withContext Picked(outName, if (png) "image/png" else "image/jpeg", clean.size.toLong()) {
                ByteArrayInputStream(clean)
            }
        }
        if (size > MAX_FILE_BYTES) throw FileTooLargeException()
        Picked(name, mime, size) { resolver.openInputStream(uri) ?: throw IOException("cannot open") }
    }

    private fun stripImage(uri: Uri, png: Boolean): ByteArray {
        fun open() = resolver.openInputStream(uri) ?: throw IOException("cannot open")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("decode failed")
        if (!png) {
            val orientation = runCatching {
                open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        if (out.size() > MAX_FILE_BYTES) throw FileTooLargeException()
        return out.toByteArray()
    }

    /** Encrypts and uploads; keeps the ciphertext locally so our own files stay viewable. */
    suspend fun upload(channelId: String, p: Picked): FileRef = withContext(Dispatchers.IO) {
        val key = FileCrypto.newKey()
        val tmp = File(dir, "up-${System.nanoTime()}.tmp")
        try {
            val plain = p.open().use { input ->
                tmp.outputStream().buffered().use { out -> FileCrypto.encrypt(key, input, out, MAX_FILE_BYTES) }
            }
            val id = api.uploadEncrypted(channelId, tmp).id
            if (!ID.matches(id)) throw IOException("bad id")
            if (!tmp.renameTo(blob(id))) {
                runCatching { api.deleteFile(id) }
                throw IOException("store failed")
            }
            FileRef(id, p.name, plain, p.mime, Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            key.fill(0)
        }
    }

    fun discard(id: String) {
        runCatching { blob(id).delete() }
    }

    private suspend fun ensure(ref: FileRef): File = withContext(Dispatchers.IO) {
        val f = blob(ref.id)
        if (f.exists()) return@withContext f
        val tmp = File(dir, "dl-${System.nanoTime()}.tmp")
        try {
            api.downloadFile(ref.id, tmp, FileCrypto.encryptedSize(ref.size) + FileCrypto.CHUNK)
            if (!tmp.renameTo(f)) throw IOException("store failed")
            f
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun keyOf(ref: FileRef): ByteArray = Base64.getUrlDecoder().decode(ref.key)

    /** Decrypts into [out]. A file that fails authentication is dropped so it is fetched again. */
    suspend fun decryptTo(ref: FileRef, out: OutputStream) = withContext(Dispatchers.IO) {
        val f = ensure(ref)
        val key = keyOf(ref)
        try {
            f.inputStream().buffered().use { FileCrypto.decrypt(key, it, out, MAX_FILE_BYTES) }
        } catch (e: java.security.GeneralSecurityException) {
            f.delete()
            throw e
        } finally {
            key.fill(0)
        }
    }

    suspend fun saveTo(ref: FileRef, uri: Uri) = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("cannot open")
        try {
            out.use { decryptTo(ref, it) }
        } catch (e: Throwable) {
            // Never leave a partly decrypted (possibly tampered) file behind.
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
    }

    /** A down-sampled bitmap for inline display, or null. */
    suspend fun image(ref: FileRef): Bitmap? = withContext(Dispatchers.IO) {
        if (ref.size > MAX_PREVIEW_BYTES) return@withContext null
        val bos = ByteArrayOutputStream()
        decryptTo(ref, bos)
        val bytes = bos.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 15L * 1024 * 1024
        private const val MAX_PHOTO_SIDE = 2560
        private const val PREVIEW_SIDE = 1024
        val ID = Regex("[0-9a-fA-F-]{36}")

        private fun blobDir(context: Context) = File(context.noBackupFilesDir, "blobs").apply { mkdirs() }

        /** Removes every stored attachment (sign-out). */
        fun wipe(context: Context) {
            blobDir(context).listFiles()?.forEach { f ->
                runCatching { f.delete() }
            }
        }

        /** Names come from other people: no paths, no control characters, bounded length. */
        fun sanitizeName(raw: String): String {
            val s = raw.substringAfterLast('/').substringAfterLast('\\')
                .filter { !it.isISOControl() && it != '‮' && it != '‭' }
                .trim().trimStart('.')
                .take(100)
            return s.ifEmpty { "file" }
        }
    }
}
