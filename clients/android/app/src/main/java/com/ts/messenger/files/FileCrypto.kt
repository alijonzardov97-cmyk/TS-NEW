package com.ts.messenger.files

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FileTooLargeException : Exception("file too large")

/**
 * Streaming authenticated encryption for attachments (STREAM construction on AES-256-GCM).
 *
 * A fresh random 256-bit key is used for every file and travels only inside the end-to-end
 * encrypted chat message. The file is cut into 64 KiB chunks; the nonce of a chunk is its 64-bit
 * big-endian index plus a final-chunk flag, so chunks cannot be reordered, dropped, duplicated or
 * truncated without the tag check failing. The server only ever sees the ciphertext.
 */
object FileCrypto {
    const val CHUNK = 64 * 1024
    private const val TAG_BYTES = 16
    private val AAD = "ts-file-v1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** Size of the ciphertext for a plaintext of [plain] bytes. */
    fun encryptedSize(plain: Long): Long = plain + (plain / CHUNK + 1) * TAG_BYTES

    private fun nonce(index: Long, last: Boolean): ByteArray {
        val n = ByteArray(12)
        ByteBuffer.wrap(n).putLong(index)
        if (last) n[11] = 1
        return n
    }

    private fun cipher(mode: Int, key: ByteArray, index: Long, last: Boolean): Cipher {
        require(key.size == 32) { "bad key" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(index, last)))
        c.updateAAD(AAD)
        return c
    }

    private fun readChunk(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var n = 0
        while (n < size) {
            val r = input.read(buf, n, size - n)
            if (r < 0) break
            n += r
        }
        return if (n == size) buf else buf.copyOf(n)
    }

    /** Returns the number of plaintext bytes processed. */
    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream, maxPlain: Long): Long {
        var cur = readChunk(input, CHUNK)
        var index = 0L
        var total = 0L
        while (true) {
            val next = if (cur.size == CHUNK) readChunk(input, CHUNK) else ByteArray(0)
            val last = next.isEmpty()
            total += cur.size
            if (total > maxPlain) throw FileTooLargeException()
            output.write(cipher(Cipher.ENCRYPT_MODE, key, index, last).doFinal(cur))
            if (last) return total
            cur = next
            index++
        }
    }

    /** Throws [GeneralSecurityException] if the data was modified, reordered or truncated. */
    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream, maxPlain: Long): Long {
        val size = CHUNK + TAG_BYTES
        var cur = readChunk(input, size)
        var index = 0L
        var total = 0L
        while (true) {
            val next = if (cur.size == size) readChunk(input, size) else ByteArray(0)
            val last = next.isEmpty()
            val plain = cipher(Cipher.DECRYPT_MODE, key, index, last).doFinal(cur)
            total += plain.size
            if (total > maxPlain) throw FileTooLargeException()
            output.write(plain)
            if (last) return total
            cur = next
            index++
        }
    }
}
