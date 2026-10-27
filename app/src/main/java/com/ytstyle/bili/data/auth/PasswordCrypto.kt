package com.ytstyle.bili.data.auth

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * RSA encryption of the login password, matching how Bilibili's web client
 * prepares the `password` field.
 *
 * The protocol is:
 *
 *  1. `GET x/passport-login/web/key` returns a PEM public key and a `hash`.
 *  2. The client encrypts **`hash + rawPassword`** (not the password alone)
 *     using RSA with PKCS#1 v1.5 padding.
 *  3. The ciphertext is Base64-encoded and sent as `password`.
 *
 * Nothing here is persisted; the plaintext password exists only for the
 * duration of the request.
 *
 * `java.util.Base64` is used rather than `android.util.Base64` so this class
 * runs unchanged in JVM unit tests. It requires API 26, which is this app's
 * `minSdk`.
 */
object PasswordCrypto {

    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val ALGORITHM = "RSA"

    /**
     * @return Base64 of the RSA-encrypted `hash + [rawPassword]`.
     * @throws IllegalArgumentException if [publicKeyPem] is not a usable SPKI key.
     */
    fun encrypt(publicKeyPem: String, hash: String, rawPassword: String): String {
        val der = decodePem(publicKeyPem)
        val publicKey = KeyFactory.getInstance(ALGORITHM)
            .generatePublic(X509EncodedKeySpec(der))

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        val payload = (hash + rawPassword).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(cipher.doFinal(payload))
    }

    /**
     * Strips the PEM armour and decodes the body.
     *
     * The endpoint has been observed returning the key with `\n` escapes rather
     * than real newlines, so all whitespace is removed rather than only line
     * breaks.
     */
    internal fun decodePem(pem: String): ByteArray {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\n", "")
            .filterNot { it.isWhitespace() }

        require(body.isNotEmpty()) { "public key is empty" }
        return Base64.getDecoder().decode(body)
    }
}
