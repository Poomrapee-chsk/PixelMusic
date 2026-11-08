package com.unshoo.pixelmusic.data.navidrome

import com.unshoo.pixelmusic.data.stream.CloudStreamProxy
import com.unshoo.pixelmusic.data.stream.CloudStreamSecurity
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local proxy that resolves `navidrome://{songId}` URIs to the Subsonic `stream.view`
 * endpoint. Keeping the signed URL inside the proxy means credentials never reach
 * ExoPlayer's cache keys, the media session, or other apps.
 */
@Singleton
class NavidromeStreamProxy @Inject constructor(
    private val repository: NavidromeRepository,
    private val credentialsStore: NavidromeCredentialsStore,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<String>(okHttpClient) {

    // Only the configured server is a valid upstream (self-hosted servers often use http/LAN IPs).
    override val allowedHostSuffixes: Set<String>
        get() = credentialsStore.serverHost?.let { setOf(it) } ?: setOf("invalid.invalid")

    // Signing is a cheap local MD5, so re-sign on every request; this way a re-login
    // (new server or password) takes effect immediately.
    override val cacheExpirationMs: Long = 0L
    override val proxyTag: String = "NavidromeStreamProxy"
    override val routePath: String = "/navidrome/{songId}"
    override val routeParamName: String = "songId"
    override val uriScheme: String = NavidromeRepository.URI_SCHEME
    override val routePrefix: String = "/navidrome"

    override fun parseRouteParam(value: String): String? = value.takeIf { it.isNotBlank() }
    override fun validateId(id: String): Boolean = CloudStreamSecurity.validateNavidromeSongId(id)
    override fun formatIdForUrl(id: String): String = id
    override suspend fun resolveStreamUrl(id: String): String? = repository.buildStreamUrl(id)
}
