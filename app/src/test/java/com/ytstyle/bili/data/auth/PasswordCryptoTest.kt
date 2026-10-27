package com.ytstyle.bili.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Verifies the login password encryption by round-tripping it through a real
 * RSA keypair: encrypt with [PasswordCrypto], decrypt with the private key, and
 * assert the plaintext is exactly `hash + password`.
 *
 * This is the part of the login flow that can be checked without a device or an
 * account, so it is checked properly rather than assumed.
 */
class PasswordCryptoTest {

    private fun keyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /** Bilibili's PEM comes with real newlines; this is the common shape. */
    private fun pem(keys: KeyPair): String = buildString {
        append("-----BEGIN PUBLIC KEY-----\n")
        append(Base64.getEncoder().encodeToString(keys.public.encoded))
        append("\n-----END PUBLIC KEY-----")
    }

    private fun decrypt(privateKey: java.security.PrivateKey, cipherTextB64: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return String(cipher.doFinal(Base64.getDecoder().decode(cipherTextB64)), Charsets.UTF_8)
    }

    @Test
    fun `encrypts hash plus password and round trips`() {
        val keys = keyPair()
        val hash = "d1c0a3f2b4e5"
        val password = "correct horse battery staple"

        val encrypted = PasswordCrypto.encrypt(pem(keys), hash, password)

        assertEquals("$hash$password", decrypt(keys.private, encrypted))
    }

    /**
     * The order matters: encrypting the password alone would decrypt to the
     * password, and Bilibili would reject it. Guards against dropping the salt.
     */
    @Test
    fun `hash is prefixed to the password not appended`() {
        val keys = keyPair()
        val encrypted = PasswordCrypto.encrypt(pem(keys), "SALT", "PASS")
        assertEquals("SALTPASS", decrypt(keys.private, encrypted))
        assertNotEquals("PASSSALT", decrypt(keys.private, encrypted))
    }

    /** Output must be plain Base64 with no PEM armour or line breaks. */
    @Test
    fun `output is single line base64`() {
        val keys = keyPair()
        val encrypted = PasswordCrypto.encrypt(pem(keys), "h", "p")

        assertTrue("must not contain newlines", !encrypted.contains("\n"))
        assertTrue("must not contain armour", !encrypted.contains("-----"))
        // Decoding must succeed and yield exactly one RSA block (256 bytes).
        assertEquals(256, Base64.getDecoder().decode(encrypted).size)
    }

    /**
     * The endpoint has been seen returning the key with literal `\n` escapes
     * instead of newlines; both must decode to the same key.
     */
    @Test
    fun `tolerates escaped newlines and whitespace`() {
        val keys = keyPair()
        val body = Base64.getEncoder().encodeToString(keys.public.encoded)

        val withRealNewlines = "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
        val withEscapedNewlines = "-----BEGIN PUBLIC KEY-----\\n$body\\n-----END PUBLIC KEY-----"
        val withSpaces = "-----BEGIN PUBLIC KEY-----   $body   -----END PUBLIC KEY-----"

        val expected = PasswordCrypto.decodePem(withRealNewlines)
        assertEquals(expected.toList(), PasswordCrypto.decodePem(withEscapedNewlines).toList())
        assertEquals(expected.toList(), PasswordCrypto.decodePem(withSpaces).toList())
    }

    /** Non-ASCII passwords are UTF-8 encoded before encryption. */
    @Test
    fun `handles non ascii passwords`() {
        val keys = keyPair()
        val encrypted = PasswordCrypto.encrypt(pem(keys), "盐", "密码123!@#")
        assertEquals("盐密码123!@#", decrypt(keys.private, encrypted))
    }

    /** A malformed key must fail loudly rather than send a bogus password. */
    @Test
    fun `rejects an empty key`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordCrypto.encrypt("", "h", "p")
        }
    }

    @Test
    fun `rejects a non key payload`() {
        assertThrows(Exception::class.java) {
            PasswordCrypto.encrypt("-----BEGIN PUBLIC KEY-----\nnotbase64!!!\n-----END PUBLIC KEY-----", "h", "p")
        }
    }

    /**
     * A 2048-bit key can only encrypt 245 bytes with PKCS#1 v1.5. Oversized
     * input must surface as an error rather than silently truncating.
     */
    @Test
    fun `rejects input larger than the modulus allows`() {
        val keys = keyPair()
        val oversized = "x".repeat(300)
        assertThrows(Exception::class.java) {
            PasswordCrypto.encrypt(pem(keys), "", oversized)
        }
    }

    /** Sanity check that the decoded PEM is a valid SPKI structure. */
    @Test
    fun `decoded pem is a usable spki key`() {
        val keys = keyPair()
        val der = PasswordCrypto.decodePem(pem(keys))
        val rebuilt = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der))
        assertEquals(keys.public, rebuilt)
    }

    // ---- against the real key from passport.bilibili.com ----

    private fun realKeyJson(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/passport_key.json")) {
            "missing fixture: passport_key.json"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Exercises [PasswordCrypto] against Bilibili's **actual** public key.
     *
     * The live key is 1024-bit with real newlines and a trailing newline after
     * the END marker — a shape the synthetic fixtures do not reproduce. A
     * 1024-bit modulus also caps the plaintext at 117 bytes with PKCS#1 v1.5,
     * so the block size is asserted at 128 bytes.
     *
     * Note the `hash` is **not** asserted against a literal: it is a random salt
     * regenerated on every request to this endpoint. That is precisely why the
     * key must be re-fetched immediately before each login rather than cached,
     * and the caller must encrypt with the hash from the *same* response.
     */
    @Test
    fun `encrypts with bilibilis real 1024 bit key`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val payload = json.decodeFromString<com.ytstyle.bili.data.net.BiliResponse<com.ytstyle.bili.data.net.LoginKey>>(
            realKeyJson(),
        )

        assertEquals(0, payload.code)
        val loginKey = payload.data!!
        assertTrue("hash must be 16 hex chars", loginKey.hash.matches(Regex("[0-9a-f]{16}")))
        assertTrue("key must be PEM", loginKey.key.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertTrue("key must be PEM terminated", loginKey.key.trimEnd().endsWith("-----END PUBLIC KEY-----"))

        // Round-trip is impossible without the private half, but the ciphertext
        // length proves the key parsed and the block size matches 1024-bit RSA.
        val encrypted = PasswordCrypto.encrypt(loginKey.key, loginKey.hash, "hunter2")
        assertEquals(128, Base64.getDecoder().decode(encrypted).size)
    }

    private fun realKeyJson(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun decodeKey(name: String): com.ytstyle.bili.data.net.LoginKey {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString<com.ytstyle.bili.data.net.BiliResponse<com.ytstyle.bili.data.net.LoginKey>>(
            realKeyJson(name),
        ).data!!
    }

    /**
     * Pins the property that dictates the whole login flow: the `hash` is a
     * freshly generated salt on every request, while the RSA key itself is
     * stable.
     *
     * Caching either value across logins would produce a signature the server
     * rejects, so the key endpoint must be called immediately before each
     * attempt and its `hash` used with that same response.
     */
    @Test
    fun `hash rotates per request while the key stays fixed`() {
        val first = decodeKey("passport_key.json")
        val second = decodeKey("passport_key_2.json")

        assertNotEquals(
            "hash must differ between requests; if this ever passes it means the salt was cached",
            first.hash,
            second.hash,
        )
        assertEquals("the RSA key itself is stable", first.key, second.key)
    }

    /** A 1024-bit key cannot encrypt more than 117 bytes; longer must throw. */
    @Test
    fun `real key rejects oversized input`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val payload = json.decodeFromString<com.ytstyle.bili.data.net.BiliResponse<com.ytstyle.bili.data.net.LoginKey>>(
            realKeyJson(),
        )
        val loginKey = payload.data!!

        // hash (16) + password: 200 chars overflows 117 bytes.
        assertThrows(Exception::class.java) {
            PasswordCrypto.encrypt(loginKey.key, loginKey.hash, "x".repeat(200))
        }
    }
}
