package com.folio.notes.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** AES-256-GCM with a key stretched from the user's password. Nothing here touches storage. */
object Crypto {
    private const val ITERATIONS = 150_000
    private const val TEXT_PREFIX = "folio1:"
    private val FILE_MAGIC = "FOLIOENC1".toByteArray()
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plain)
    }

    /** Throws if [key] is wrong or the data was altered. */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        return cipher.doFinal(data, 12, data.size - 12)
    }

    fun encryptText(key: SecretKey, text: String): String =
        TEXT_PREFIX + Base64.encodeToString(encrypt(key, text.toByteArray()), Base64.NO_WRAP)

    fun decryptText(key: SecretKey, data: String): String =
        String(decrypt(key, Base64.decode(data.removePrefix(TEXT_PREFIX), Base64.NO_WRAP)))

    fun encryptFile(key: SecretKey, plain: ByteArray): ByteArray = FILE_MAGIC + encrypt(key, plain)
    fun isEncryptedFile(data: ByteArray) = data.size > FILE_MAGIC.size && FILE_MAGIC.indices.all { data[it] == FILE_MAGIC[it] }
    fun decryptFile(key: SecretKey, data: ByteArray): ByteArray = decrypt(key, data.copyOfRange(FILE_MAGIC.size, data.size))
}

/**
 * The password that protects locked notes. Only a salt and a check value are stored; the key itself lives in
 * memory while the vault is open and is dropped when the app leaves the screen.
 */
class Vault(private val settings: SettingsStore) {
    private val _key = MutableStateFlow<SecretKey?>(null)
    val key: StateFlow<SecretKey?> = _key
    val isOpen get() = _key.value != null

    suspend fun hasPassword() = settings.current().vaultSalt != null

    /** A password turned into a key, not yet in use. */
    class Prepared(val salt: String, val check: String, val key: SecretKey)

    suspend fun prepare(password: String): Prepared = withContext(Dispatchers.Default) {
        val salt = Crypto.newSalt()
        val key = Crypto.deriveKey(password, salt)
        Prepared(Base64.encodeToString(salt, Base64.NO_WRAP), Crypto.encryptText(key, CHECK), key)
    }

    /** Makes [prepared] the notes password and opens the vault with it. */
    suspend fun adopt(prepared: Prepared) {
        settings.setVault(prepared.salt, prepared.check)
        _key.value = prepared.key
    }

    suspend fun setPassword(password: String): SecretKey = prepare(password).also { adopt(it) }.key

    /** Opens the vault; false if the password is wrong. */
    suspend fun open(password: String): Boolean = withContext(Dispatchers.Default) {
        val key = keyFor(password) ?: return@withContext false
        _key.value = key
        true
    }

    suspend fun keyFor(password: String): SecretKey? = withContext(Dispatchers.Default) {
        val s = settings.current()
        val salt = s.vaultSalt ?: return@withContext null
        val key = Crypto.deriveKey(password, Base64.decode(salt, Base64.NO_WRAP))
        try { if (Crypto.decryptText(key, s.vaultCheck.orEmpty()) == CHECK) key else null } catch (e: Exception) { null }
    }

    fun close() { _key.value = null }

    suspend fun clearPassword() { settings.setVault(null, null); close() }

    private companion object { const val CHECK = "folio-vault" }
}
