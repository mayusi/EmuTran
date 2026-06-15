package io.github.mayusi.emutran.data.source

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Network interceptor that rejects any 3xx redirect that downgrades from
 * HTTPS to HTTP.
 *
 * Rationale: The shared OkHttpClient has followSslRedirects(true), which
 * permits GitHub or Gitea to silently redirect an HTTPS download to a plain
 * HTTP URL — exposing the downloaded bytes to MITM substitution. Both the APK
 * downloader (ApkDownloader) and the driver stager (DriverStager) build a
 * private OkHttpClient that adds this protection without mutating the shared
 * singleton's connection pool.
 *
 * What this does:
 *   - On a 3xx response, checks the Location header.
 *   - If the original request URL was https:// and Location is http://,
 *     throws IOException("HTTPS→HTTP redirect blocked") so the caller treats
 *     it as a transient error (the download loop will retry / eventually fail
 *     with a clear message rather than silently installing unverified bytes).
 *
 * What this does NOT block:
 *   - Requests that originate as http:// (ppsspp.org) — their cleartext is
 *     explicitly permitted in network_security_config.xml and there is no
 *     downgrade happening (they were always HTTP).
 *   - HTTPS→HTTPS redirects (CDN hops, www→non-www, etc.) — those are fine.
 *   - Non-redirect responses — the interceptor is a no-op for 2xx/4xx/5xx.
 *
 * Tradeoff: We throw on the first 3xx we see that downgrades, which means a
 * retry loop does attempt again (up to its own max attempts). That is safe — a
 * downgrading redirect is not going to un-downgrade on retry, so exhausting
 * retries and failing is the right outcome.
 */
internal class HttpsDowngradeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only inspect redirects.
        if (response.code in 300..399) {
            val location = response.header("Location") ?: return response
            val isHttpsOrigin = request.url.scheme.equals("https", ignoreCase = true)
            val isHttpDestination = location.startsWith("http://", ignoreCase = true)
            if (isHttpsOrigin && isHttpDestination) {
                response.close()
                throw IOException(
                    "HTTPS→HTTP redirect blocked (${request.url.host} → $location). " +
                        "This may indicate a MITM attack or server misconfiguration. " +
                        "Download aborted for safety."
                )
            }
        }
        return response
    }
}
