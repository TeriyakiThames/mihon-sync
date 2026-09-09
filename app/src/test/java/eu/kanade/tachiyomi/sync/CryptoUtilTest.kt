package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.sync.crypto.CryptoUtil
import eu.kanade.tachiyomi.sync.crypto.SyncPairingUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

class CryptoUtilTest {

    @Test
    fun `generateKey produces valid 256-bit random keys`() {
        val rawKey1 = CryptoUtil.generateKey()
        val rawKey2 = CryptoUtil.generateKey()

        assertEquals(32, rawKey1.size)
        assertEquals(32, rawKey2.size)
        assertNotEquals(rawKey1.toList(), rawKey2.toList())

        val base64Key = CryptoUtil.generateSecretKey()
        val decoded = Base64.getDecoder().decode(base64Key)
        assertEquals(32, decoded.size)
    }

    @Test
    fun `generateRoomId produces unique 16 hex character identifiers`() {
        val room1 = CryptoUtil.generateRoomId()
        val room2 = CryptoUtil.generateRoomId()

        assertEquals(16, room1.length)
        assertEquals(16, room2.length)
        assertNotEquals(room1, room2)
        assertTrue(room1.matches(Regex("^[0-9a-fA-F]{16}$")))
    }

    @Test
    fun `encryption and decryption roundtrip preserves UTF-8 plaintext`() {
        val key = CryptoUtil.generateSecretKey()
        val originalText = "Hello Mihon E2EE Sync! UTF-8: 素晴らしいマンガ 🚀✨ — 1234567890"

        val ciphertext = CryptoUtil.encryptString(originalText, key)
        assertNotEquals(originalText, ciphertext)

        val decryptedText = CryptoUtil.decryptString(ciphertext, key)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun `unique random IV ensures distinct ciphertexts for identical plaintext`() {
        val key = CryptoUtil.generateSecretKey()
        val plaintext = "Same plaintext to be encrypted multiple times"

        val cipher1 = CryptoUtil.encryptString(plaintext, key)
        val cipher2 = CryptoUtil.encryptString(plaintext, key)
        val cipher3 = CryptoUtil.encryptString(plaintext, key)

        assertNotEquals(cipher1, cipher2)
        assertNotEquals(cipher2, cipher3)
        assertNotEquals(cipher1, cipher3)

        assertEquals(plaintext, CryptoUtil.decryptString(cipher1, key))
        assertEquals(plaintext, CryptoUtil.decryptString(cipher2, key))
        assertEquals(plaintext, CryptoUtil.decryptString(cipher3, key))
    }

    @Test
    fun `tampering with ciphertext or authentication tag fails decryption`() {
        val key = CryptoUtil.generateSecretKey()
        val plaintext = "Integrity protected content"
        val ciphertext = CryptoUtil.encryptString(plaintext, key)

        val rawBytes = Base64.getDecoder().decode(ciphertext)
        // Corrupt the last byte (part of the 128-bit authentication tag)
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0x5A).toByte()
        val corruptedCiphertext = Base64.getEncoder().encodeToString(rawBytes)

        assertThrows(Exception::class.java) {
            CryptoUtil.decryptString(corruptedCiphertext, key)
        }
    }

    @Test
    fun `tampering with IV fails decryption`() {
        val key = CryptoUtil.generateSecretKey()
        val ciphertext = CryptoUtil.encryptString("Payload with tampered IV", key)

        val rawBytes = Base64.getDecoder().decode(ciphertext)
        // Corrupt the first byte (part of the 12-byte IV)
        rawBytes[0] = (rawBytes[0].toInt() xor 0xFF).toByte()
        val corruptedCiphertext = Base64.getEncoder().encodeToString(rawBytes)

        assertThrows(Exception::class.java) {
            CryptoUtil.decryptString(corruptedCiphertext, key)
        }
    }

    @Test
    fun `decrypting with mismatched secret key fails authentication`() {
        val key1 = CryptoUtil.generateSecretKey()
        val key2 = CryptoUtil.generateSecretKey()

        val ciphertext = CryptoUtil.encryptString("Secret data for room A", key1)

        assertThrows(Exception::class.java) {
            CryptoUtil.decryptString(ciphertext, key2)
        }
    }

    @Test
    fun `decrypting invalid or short ciphertext throws IllegalArgumentException`() {
        val key = CryptoUtil.generateSecretKey()

        // Too short (< 28 bytes for 12-byte IV + 16-byte tag)
        val shortBase64 = Base64.getEncoder().encodeToString(ByteArray(10))
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtil.decrypt(shortBase64, key)
        }
    }

    @Test
    fun `invalid key length throws IllegalArgumentException`() {
        val invalidKey = Base64.getEncoder().encodeToString(ByteArray(16)) // 128-bit key instead of 256-bit

        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtil.encryptString("test", invalidKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtil.decryptString("dummy", invalidKey)
        }
    }

    @Test
    fun `pairing URI creation and parsing roundtrip`() {
        val serverUrl = "https://sync.mihon.app"
        val roomId = CryptoUtil.generateRoomId()
        val secretKey = CryptoUtil.generateSecretKey()

        val uri = SyncPairingUtil.createPairingUri(serverUrl, roomId, secretKey)
        assertTrue(uri.startsWith("mihon-sync://pair?"))

        val parsed = SyncPairingUtil.parsePairingUri(uri)
        assertEquals(serverUrl, parsed.serverUrl)
        assertEquals(roomId, parsed.roomId)
        assertEquals(secretKey, parsed.secretKey)
    }

    @Test
    fun `encryption and decryption roundtrip of empty string`() {
        val key = CryptoUtil.generateSecretKey()
        val emptyString = ""
        val ciphertext = CryptoUtil.encryptString(emptyString, key)
        val decrypted = CryptoUtil.decryptString(ciphertext, key)
        assertEquals(emptyString, decrypted)
    }

    @Test
    fun `1-bit alteration in ciphertext body fails decryption`() {
        val key = CryptoUtil.generateSecretKey()
        val plaintext = "Longer plaintext body designed to test bit flips in the encrypted payload body"
        val ciphertext = CryptoUtil.encryptString(plaintext, key)

        val rawBytes = Base64.getDecoder().decode(ciphertext)
        // Flip a bit at index 14 (inside the ciphertext body, between IV (0..11) and Tag (last 16 bytes))
        rawBytes[14] = (rawBytes[14].toInt() xor 0x01).toByte()
        val corruptedCiphertext = Base64.getEncoder().encodeToString(rawBytes)

        assertThrows(Exception::class.java) {
            CryptoUtil.decryptString(corruptedCiphertext, key)
        }
    }

    @Test
    fun `off-by-one key lengths throw IllegalArgumentException`() {
        val key31 = Base64.getEncoder().encodeToString(ByteArray(31))
        val key33 = Base64.getEncoder().encodeToString(ByteArray(33))

        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtil.encryptString("payload", key31)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtil.encryptString("payload", key33)
        }
    }

    @Test
    fun `whitespace in Base64 ciphertext or key is safely trimmed`() {
        val key = CryptoUtil.generateSecretKey()
        val paddedKey = "   $key \n"
        val plaintext = "Whitespace resilience test"
        val ciphertext = CryptoUtil.encryptString(plaintext, paddedKey)
        val paddedCiphertext = " \t$ciphertext \r\n"
        val decrypted = CryptoUtil.decryptString(paddedCiphertext, paddedKey)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `pairing URI parsing handles alternative parameter aliases`() {
        val uriWithAliases = "mihon-sync://pair?server=https%3A%2F%2Fsync.mihon.app&room=abc12345&key=secret123"
        val parsed = SyncPairingUtil.parsePairingUri(uriWithAliases)
        assertEquals("https://sync.mihon.app", parsed.serverUrl)
        assertEquals("abc12345", parsed.roomId)
        assertEquals("secret123", parsed.secretKey)
    }

    @Test
    fun `pairing URI parsing handles case-insensitive scheme and host`() {
        val uriUpper = "MIHON-SYNC://PAIR?serverUrl=https%3A%2F%2Fsync.mihon.app&roomId=abc12345&key=secret123"
        val parsed = SyncPairingUtil.parsePairingUri(uriUpper)
        assertEquals("https://sync.mihon.app", parsed.serverUrl)
        assertEquals("abc12345", parsed.roomId)
        assertEquals("secret123", parsed.secretKey)
    }

    @Test
    fun `pairing URI parsing rejects missing required parameters`() {
        val missingKeyUri = "mihon-sync://pair?serverUrl=https%3A%2F%2Fsync.mihon.app&roomId=abc12345"
        assertThrows(IllegalArgumentException::class.java) {
            SyncPairingUtil.parsePairingUri(missingKeyUri)
        }

        val invalidSchemeUri = "https://pair?serverUrl=https%3A%2F%2Fsync.mihon.app&roomId=abc12345&key=secret"
        assertThrows(IllegalArgumentException::class.java) {
            SyncPairingUtil.parsePairingUri(invalidSchemeUri)
        }
    }
}

