package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.KrakenApiConstants
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

object KrakenSigning {
    fun sign(path: String, nonce: String, postData: String, base64Secret: String): String {
        try {
            val sha2 = MessageDigest
                .getInstance(KrakenApiConstants.SHA_256)
                .digest((nonce + postData).toByteArray(Charsets.UTF_8))

            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val hmacMessage = pathBytes + sha2

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
