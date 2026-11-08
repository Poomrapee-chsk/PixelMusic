@file:Suppress("DEPRECATION")
package com.unshoo.pixelmusic.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Subsonic/Navidrome server credentials (encrypted at rest) and knows how to
 * sign Subsonic REST URLs with them.
 *
 * Signing is done per request (fresh salt every time) so URLs persisted in the database
 * (e.g. cover art) never contain credentials. Image loading and streaming add the auth
 * query parameters on the fly via [signIfNavidromeUrl] / [buildSignedUrl].
 */
@Singleton
class NavidromeCredentialsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    data class Credentials(
        val serverUrl: String,
        val username: String,
        val password: String,
        val useLegacyAuth: Boolean
    )

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "PixelMusic"
        private const val PREFS_NAME = "navidrome_prefs"
        private const val KEY_SERVER = "navidrome_server_url"
        private const val KEY_USERNAME = "navidrome_username"
        private const val KEY_PASSWORD = "navidrome_password"
        private const val KEY_LEGACY_AUTH = "navidrome_legacy_auth"
        private const val KEY_SERVER_TYPE = "navidrome_server_type"
        private const val KEY_SERVER_VERSION = "navidrome_server_version"
        private const val KEY_LAST_SYNC = "navidrome_last_sync"

        private val AUTH_QUERY_PARAMS = setOf("u", "t", "s", "p", "v", "c", "f")

        /** Normalizes user input like "music.example.com/" into "https://music.example.com". */
        fun normalizeServerUrl(raw: String): String? {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return null
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "https://$value"
            }
            // Users frequently paste the web UI URL; the REST API lives at <base>/rest.
            value = value.removeSuffix("/rest").removeSuffix("/app").removeSuffix("/#").trimEnd('/')
            val parsed = value.toHttpUrlOrNull() ?: return null
            if (parsed.host.isBlank()) return null
            return value
        }

        private fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun hexEncode(input: String): String =
            input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    }

    private val random = SecureRandom()

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Timber.e(e, "NavidromeCredentialsStore: EncryptedSharedPreferences unavailable, using private prefs")
        context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cached: Credentials? = readFromPrefs()

    @Volatile
    private var cachedServerHost: String? = cached?.serverUrl?.toHttpUrlOrNull()?.host

    val credentials: Credentials? get() = cached
    val isConfigured: Boolean get() = cached != null
    val serverHost: String? get() = cachedServerHost

    val serverType: String? get() = prefs.getString(KEY_SERVER_TYPE, null)
    val serverVersion: String? get() = prefs.getString(KEY_SERVER_VERSION, null)
    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC, value).apply() }

    private fun readFromPrefs(): Credentials? {
        val server = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val pass = prefs.getString(KEY_PASSWORD, null) ?: return null
        return Credentials(server, user, pass, prefs.getBoolean(KEY_LEGACY_AUTH, false))
    }

    fun save(credentials: Credentials, serverType: String?, serverVersion: String?) {
        prefs.edit()
            .putString(KEY_SERVER, credentials.serverUrl)
            .putString(KEY_USERNAME, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .putBoolean(KEY_LEGACY_AUTH, credentials.useLegacyAuth)
            .putString(KEY_SERVER_TYPE, serverType)
            .putString(KEY_SERVER_VERSION, serverVersion)
            .apply()
        cached = credentials
        cachedServerHost = credentials.serverUrl.toHttpUrlOrNull()?.host
    }

    fun clear() {
        prefs.edit().clear().apply()
        cached = null
        cachedServerHost = null
    }

    /** Unsigned REST endpoint URL, safe to persist (e.g. cover art). */
    fun buildUnsignedUrl(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        credentials: Credentials? = cached
    ): HttpUrl? {
        val base = credentials?.serverUrl?.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /** Fully signed REST URL including auth parameters. Never persist the result. */
    fun buildSignedUrl(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        credentials: Credentials? = cached,
        json: Boolean = true
    ): HttpUrl? {
        val unsigned = buildUnsignedUrl(endpoint, params, credentials) ?: return null
        return sign(unsigned, credentials ?: return null, json)
    }

    /**
     * If [url] targets the configured Subsonic server's REST API and carries no auth yet,
     * returns a signed copy; otherwise returns null.
     */
    fun signIfNavidromeUrl(url: HttpUrl): HttpUrl? {
        val creds = cached ?: return null
        val host = cachedServerHost ?: return null
        if (!url.host.equals(host, ignoreCase = true)) return null
        if (!url.encodedPath.contains("/rest/")) return null
        if (url.queryParameter("u") != null) return null
        return sign(url, creds, json = false)
    }

    private fun sign(url: HttpUrl, credentials: Credentials, json: Boolean): HttpUrl {
        val builder = url.newBuilder()
        AUTH_QUERY_PARAMS.forEach { builder.removeAllQueryParameters(it) }
        builder.addQueryParameter("u", credentials.username)
        if (credentials.useLegacyAuth) {
            builder.addQueryParameter("p", "enc:${hexEncode(credentials.password)}")
        } else {
            val salt = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            builder.addQueryParameter("t", md5Hex(credentials.password + salt))
            builder.addQueryParameter("s", salt)
        }
        builder.addQueryParameter("v", API_VERSION)
        builder.addQueryParameter("c", CLIENT_NAME)
        if (json) builder.addQueryParameter("f", "json")
        return builder.build()
    }
}
