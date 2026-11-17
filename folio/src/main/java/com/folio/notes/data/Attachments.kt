package com.folio.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey

/** Pictures attached to notes, kept as files in app-private storage. Those of locked notes are encrypted. */
class Attachments(private val context: Context) {
    private val dir get() = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(name: String) = File(dir, File(name).name)

    fun add(bytes: ByteArray, extension: String, key: SecretKey? = null): String {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "jpg" }
        val name = "${UUID.randomUUID()}.$ext"
        file(name).writeBytes(if (key != null) Crypto.encryptFile(key, bytes) else bytes)
        return name
    }

    fun add(uri: Uri, key: SecretKey? = null): String? = try {
        val type = context.contentResolver.getType(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
        context.contentResolver.openInputStream(uri)?.use { add(it.readBytes(), ext, key) }
    } catch (e: Exception) { null }

    fun add(bitmap: Bitmap, key: SecretKey? = null): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return add(out.toByteArray(), "png", key)
    }

    fun isEncrypted(name: String): Boolean = try {
        file(name).inputStream().use { s -> ByteArray(16).let { it.copyOf(maxOf(0, s.read(it))) } }.let(Crypto::isEncryptedFile)
    } catch (e: Exception) { false }

    /** The picture's bytes, decrypted if needed; null if it is missing or [key] cannot open it. */
    fun read(name: String, key: SecretKey?): ByteArray? = try {
        val data = file(name).readBytes()
        if (!Crypto.isEncryptedFile(data)) data else key?.let { Crypto.decryptFile(it, data) }
    } catch (e: Exception) { null }

    /** Re-writes a picture encrypted with [to], or in the clear when [to] is null. */
    fun recrypt(name: String, from: SecretKey?, to: SecretKey?) {
        val plain = read(name, from) ?: return
        file(name).writeBytes(if (to != null) Crypto.encryptFile(to, plain) else plain)
    }

    fun delete(names: Collection<String>) = names.forEach { file(it).delete() }

    /** Removes files no note refers to any more. */
    fun sweep(referenced: Set<String>, olderThan: Long = System.currentTimeMillis() - 60_000) {
        dir.listFiles()?.forEach { if (it.name !in referenced && it.lastModified() < olderThan) it.delete() }
    }

    companion object {
        fun mimeType(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
        }
    }
}
