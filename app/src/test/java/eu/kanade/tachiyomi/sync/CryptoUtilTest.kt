package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.sync.crypto.CryptoUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CryptoUtilTest {

    private val testKey = CryptoUtil.generateSecretKey()

    @Test
    fun `encryptString and decryptString roundtrip correctly`() {
        val original = "Hello, Mihon Sync with GZIP compression!"
        val ciphertext = CryptoUtil.encryptString(original, testKey)
        val decrypted = CryptoUtil.decryptString(ciphertext, testKey)

        assertEquals(original, decrypted)
    }

    @Test
    fun `encryptString compresses large JSON payload significantly`() {
        // Construct a realistic repetitive JSON sync payload (~100 KB)
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("{\"mangas\":[")
        for (i in 1..200) {
            if (i > 1) jsonBuilder.append(",")
            jsonBuilder.append(
                """{"source":1,"url":"/manga/$i","title":"Manga Title $i","description":"A long description of the manga story and adventure details repeated multiple times.","genre":["Action","Adventure","Fantasy"],"favorite":true}"""
            )
        }
        jsonBuilder.append("]}")
        val jsonPayload = jsonBuilder.toString()
        val rawByteSize = jsonPayload.toByteArray(Charsets.UTF_8).size

        val ciphertext = CryptoUtil.encryptString(jsonPayload, testKey)
        val decrypted = CryptoUtil.decryptString(ciphertext, testKey)

        // Raw size is ~38 KB
        assertTrue(rawByteSize > 20_000, "Raw payload should be > 20KB")
        // Base64 ciphertext with GZIP should be less than 20% of the raw byte size
        val ciphertextSize = ciphertext.length
        assertTrue(
            ciphertextSize < rawByteSize / 3,
            "Ciphertext ($ciphertextSize chars) should be significantly smaller than raw JSON ($rawByteSize bytes)"
        )
        assertEquals(jsonPayload, decrypted)
    }

    @Test
    fun `decryptString seamlessly handles legacy uncompressed ciphertext`() {
        val legacyText = "{\"mangas\":[],\"chapters\":[],\"legacy\":true}"
        val rawPlainBytes = legacyText.toByteArray(Charsets.UTF_8)

        // Directly encrypt raw uncompressed bytes without GZIP (simulating legacy clients)
        val legacyCiphertext = CryptoUtil.encrypt(rawPlainBytes, testKey)

        // decryptString should detect absence of GZIP header and return raw UTF-8 string
        val decrypted = CryptoUtil.decryptString(legacyCiphertext, testKey)
        assertEquals(legacyText, decrypted)
    }

    @Test
    fun `compressGzip and decompressGzipIfCompressed roundtrip`() {
        val originalBytes = "Test bytes to compress".toByteArray(Charsets.UTF_8)
        val compressed = CryptoUtil.compressGzip(originalBytes)

        assertTrue(compressed.size >= 2)
        // Verify GZIP magic bytes 0x1F, 0x8B
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])

        val decompressed = CryptoUtil.decompressGzipIfCompressed(compressed)
        assertEquals(String(originalBytes, Charsets.UTF_8), String(decompressed, Charsets.UTF_8))
    }
}
