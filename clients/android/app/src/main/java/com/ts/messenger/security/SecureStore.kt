package com.ts.messenger.security

import android.content.Context
import java.io.File

/**
 * Tiny encrypted key-value store. Every value is encrypted with [KeystoreVault] and written
 * to the app's no-backup directory, so it is excluded from cloud backups and device transfer.
 */
class SecureStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9_.-]{1,64}"))) { "bad key name" }
        return File(dir, "$name.bin")
    }

    @Synchronized
    fun put(name: String, value: ByteArray) {
        val blob = KeystoreVault.encrypt(value, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(blob)
        // Atomic replace so a crash never leaves a half-written secret.
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            check(tmp.renameTo(file(name))) { "could not store value" }
        }
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        return try {
            KeystoreVault.decrypt(f.readBytes(), name)
        } catch (_: Exception) {
            // Corrupted or key invalidated: treat as absent rather than crash.
            null
        }
    }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))

    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)

    @Synchronized
    fun remove(name: String) {
        file(name).delete()
    }

    /** Wipes every stored value and destroys the master key. Used on sign-out. */
    @Synchronized
    fun wipeAll() {
        dir.listFiles()?.forEach { f ->
            runCatching {
                // Best effort overwrite before delete.
                f.writeBytes(ByteArray(f.length().toInt()))
            }
            f.delete()
        }
        KeystoreVault.destroyKey()
    }
}
