package eu.kanade.tachiyomi.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object PkceUtil {

    fun generateCodeVerifier(): String {
        val codeVerifier = ByteArray(50)
        SecureRandom().nextBytes(codeVerifier)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codeVerifier)
    }

    /**
     * Computes the S256 PKCE code challenge: BASE64URL(SHA-256(verifier)). Using S256 instead
     * of "plain" ensures the value sent in the redirect can't be replayed at the token endpoint
     * by an app that intercepts the custom-scheme callback.
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }
}
