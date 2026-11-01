package eu.kanade.tachiyomi.network

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class VerboseHttpLoggingTest {

    private fun logFor(request: Request): String {
        val lines = mutableListOf<String>()
        val canned = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Set-Cookie", "session=SECRET_SET_COOKIE")
                .body("".toResponseBody())
                .build()
        }
        OkHttpClient.Builder()
            .addInterceptor(verboseHttpLoggingInterceptor { lines += it })
            .addInterceptor(canned)
            .build()
            .newCall(request)
            .execute()
            .close()
        return lines.joinToString("\n")
    }

    @Test
    fun `credentials stay out of the log and the rest stays in`() {
        val log = logFor(
            Request.Builder()
                .url("https://example.org/path?page=2&apiKey=SECRET_QUERY")
                .header("Authorization", "Bearer SECRET_AUTH")
                .header("Cookie", "cf_clearance=SECRET_COOKIE")
                .header("Referer", "https://example.org/")
                .build(),
        )

        log shouldNotContain "SECRET_AUTH"
        log shouldNotContain "SECRET_COOKIE"
        log shouldNotContain "SECRET_SET_COOKIE"
        log shouldNotContain "SECRET_QUERY"

        log shouldContain "example.org/path"
        log shouldContain "page=2"
        log shouldContain "Referer: https://example.org/"
    }
}
