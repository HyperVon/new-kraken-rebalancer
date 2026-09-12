package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.KrakenApiConstants
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

object KrakenSigning {
    fun sign(path: String, nonce: String, postData: String, base64Secret: String): String =
        signMessage(path.toByteArray(Charsets.UTF_8) + sha256(nonce + postData), base64Secret)

    /**
     * Funding (Beta) request signing: the signed path includes the query string and the nonce
     * travels in the API-Nonce header instead of the request body.
     */
    fun signGet(signedPath: String, nonce: String, base64Secret: String): String =
        signMessage(signedPath.toByteArray(Charsets.UTF_8) + sha256(nonce), base64Secret)

    private fun sha256(value: String): ByteArray = MessageDigest
        .getInstance(KrakenApiConstants.SHA_256)
        .digest(value.toByteArray(Charsets.UTF_8))

    private fun signMessage(hmacMessage: ByteArray, base64Secret: String): String {
        try {
            val mac = Mac.getInstance(KrakenApiConstants.HMAC_SHA512)
            val secretDecoded = Base64.decode(base64Secret)
            val secretSpec = SecretKeySpec(secretDecoded, KrakenApiConstants.HMAC_SHA512)
            mac.init(secretSpec)

            val sigBytes = mac.doFinal(hmacMessage)
            return Base64.encode(sigBytes)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign request", e)
        }
    }
}
