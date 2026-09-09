package eu.kanade.tachiyomi.sync.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides symmetric AES-256-GCM authenticated encryption and decryption for Mihon End-to-End Encryption (E2EE).
 *
 * Wire format: IV (12 bytes) + Ciphertext with 128-bit GCM authentication tag (N bytes), Base64-encoded.
 */
object CryptoUtil {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_SIZE_BYTES = 12 // 96-bit nonce recommended for AES-GCM
    const val TAG_BIT_LENGTH = 128 // 16-byte authentication tag
    const val KEY_SIZE_BITS = 256 // 256-bit AES key
    const val KEY_SIZE_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Generates a cryptographically secure 256-bit AES key as raw bytes.
     */
    fun generateKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE_BITS, secureRandom)
        return keyGen.generateKey().encoded
    }

    /**
     * Generates a cryptographically secure 256-bit AES key, Base64-encoded.
     */
    fun generateSecretKey(): String {
        return Base64.getEncoder().encodeToString(generateKey())
    }

    /**
     * Generates a random 16-character hexadecimal room identifier (64 bits of entropy).
     */
    fun generateRoomId(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Returns raw byte array: [12-byte IV] + [Ciphertext with 16-byte GCM Tag].
     */
    fun encrypt(plainBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == KEY_SIZE_BYTES) {
            "Secret key must be exactly $KEY_SIZE_BYTES bytes (256 bits), but was ${keyBytes.size} bytes"
        }

        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_BIT_LENGTH, iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

        val ciphertextWithTag = cipher.doFinal(plainBytes)

        val combined = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, combined, iv.size, ciphertextWithTag.size)
        return combined
    }

    /**
     * Encrypts plaintext bytes using a Base64-encoded 256-bit key.
     * Returns Base64-encoded string: Base64([12-byte IV] + [Ciphertext with 16-byte Tag]).
     */
    fun encrypt(plainBytes: ByteArray, base64Key: String): String {
        val keyBytes = decodeBase64(base64Key)
        val encryptedBytes = encrypt(plainBytes, keyBytes)
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    /**
     * Encrypts a UTF-8 plaintext string using a Base64-encoded 256-bit key.
     * Returns Base64-encoded string.
     */
    fun encryptString(plainText: String, base64Key: String): String {
        return encrypt(plainText.toByteArray(Charsets.UTF_8), base64Key)
    }

    /**
     * Decrypts combined byte array: [12-byte IV] + [Ciphertext with 16-byte GCM Tag].
     * Returns decrypted plaintext bytes.
     */
    fun decrypt(combinedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == KEY_SIZE_BYTES) {
            "Secret key must be exactly $KEY_SIZE_BYTES bytes (256 bits), but was ${keyBytes.size} bytes"
        }
        val minExpectedLength = IV_SIZE_BYTES + (TAG_BIT_LENGTH / 8)
        require(combinedBytes.size >= minExpectedLength) {
            "Ciphertext too short (${combinedBytes.size} bytes); expected at least $minExpectedLength bytes for IV and tag"
        }

        val iv = ByteArray(IV_SIZE_BYTES)
        val ciphertextWithTag = ByteArray(combinedBytes.size - IV_SIZE_BYTES)

        System.arraycopy(combinedBytes, 0, iv, 0, IV_SIZE_BYTES)
        System.arraycopy(combinedBytes, IV_SIZE_BYTES, ciphertextWithTag, 0, ciphertextWithTag.size)

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_BIT_LENGTH, iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Decrypts Base64-encoded ciphertext using a Base64-encoded 256-bit key.
     * Returns decrypted plaintext bytes.
     */
    fun decrypt(base64Ciphertext: String, base64Key: String): ByteArray {
        val keyBytes = decodeBase64(base64Key)
        val combinedBytes = decodeBase64(base64Ciphertext)
        return decrypt(combinedBytes, keyBytes)
    }

    /**
     * Decrypts Base64-encoded ciphertext into a UTF-8 plaintext string.
     */
    fun decryptString(base64Ciphertext: String, base64Key: String): String {
        return String(decrypt(base64Ciphertext, base64Key), Charsets.UTF_8)
    }

    /**
     * Decodes standard or URL-safe Base64 string into bytes.
     */
    fun decodeBase64(base64Str: String): ByteArray {
        val sanitized = base64Str.trim()
        return try {
            Base64.getDecoder().decode(sanitized)
        } catch (e: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(sanitized)
        }
    }

    /**
     * Encodes raw bytes to standard Base64 string.
     */
    fun encodeBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }
}
