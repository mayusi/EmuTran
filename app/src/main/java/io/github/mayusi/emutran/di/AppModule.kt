package io.github.mayusi.emutran.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mayusi.emutran.data.auth.GithubTokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Singletons that need explicit construction live here. Most classes use
 * @Inject constructor + @Singleton directly; this module is reserved for
 * things that aren't ours (Context-bound third-party objects, etc.).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared OkHttpClient used across all data-layer components that do
     * network I/O (GitHubReleasesSource, GiteaSource, HtmlScrapeSource,
     * ObtainiumPackParser). A single instance means one connection pool
     * for the whole app — fewer sockets, better throughput.
     *
     * Timeouts:
     *   connect 20 s — generous enough for slow/spotty mobile connections.
     *   read 60 s    — APK manifests can be slow on first byte from GitHub.
     *
     * ApkDownloader uses a separate client (it needs custom timeouts for
     * large file downloads) and is wired separately in a later pass.
     *
     * The GitHub auth interceptor added here attaches the user's optional PAT
     * to requests headed for api.github.com only. This host check is a security
     * boundary — the token must never be forwarded to download CDNs, Gitea
     * instances, ppsspp.org, or any other host. See [GithubAuthInterceptor].
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(githubTokenStore: GithubTokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // Security boundary: only attaches the PAT to api.github.com.
            .addInterceptor(GithubAuthInterceptor(githubTokenStore))
            .build()
}

/**
 * OkHttp application interceptor that adds a GitHub PAT Bearer token to
 * requests destined for api.github.com, when one is configured by the user.
 *
 * SECURITY BOUNDARY: The host check `request.url.host == "api.github.com"`
 * is the sole guard that prevents the token from leaking to other servers
 * (download CDNs, Gitea, ppsspp.org, etc.). Do not weaken this check.
 *
 * The token is read from [GithubTokenStore.currentToken] synchronously —
 * this is intentional: OkHttp interceptors run on background threads and
 * must not use runBlocking. GithubTokenStore keeps the cached value up to
 * date via a background flow collector (see its init block).
 *
 * We skip adding the header if one is already present to avoid clobbering
 * an Authorization header set by any future code path.
 */
private class GithubAuthInterceptor(
    private val store: GithubTokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Security boundary — only attach the token to the GitHub REST API.
        // Never send it to download CDNs, Gitea, ppsspp, or any other host.
        if (request.url.host != "api.github.com") {
            return chain.proceed(request)
        }

        val token = store.currentToken()
        if (token.isNullOrBlank()) {
            return chain.proceed(request)
        }

        // Skip if Authorization is already present (defensive — no current
        // code sets it, but guard against future changes).
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }

        val authed = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authed)
    }
}
