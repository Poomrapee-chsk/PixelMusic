package com.unshoo.pixelmusic.data.navidrome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Error reported by a Subsonic server inside a `status="failed"` response. */
class SubsonicApiException(val code: Int, message: String) : IOException(message) {
    companion object {
        const val CODE_WRONG_CREDENTIALS = 40
        const val CODE_TOKEN_AUTH_NOT_SUPPORTED = 41
    }
}

data class SubsonicSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistNames: List<String>,
    val album: String,
    val albumId: String?,
    val albumArtist: String?,
    val coverArt: String?,
    val durationSec: Int,
    val track: Int,
    val discNumber: Int?,
    val year: Int,
    val genre: String?,
    val bitRateKbps: Int?,
    val contentType: String?,
    val suffix: String?,
    val path: String?,
    val createdMillis: Long?
)

data class SubsonicAlbum(val id: String, val name: String, val songCount: Int)

data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
    val coverArt: String?,
    val changedMillis: Long?
)

data class SubsonicPing(val type: String?, val serverVersion: String?, val apiVersion: String?)

/**
 * Minimal Subsonic REST API client (JSON flavour). Works against Navidrome, Gonic,
 * Airsonic(-Advanced), Ampache and any other Subsonic-compatible server.
 */
@Singleton
class NavidromeApiService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialsStore: NavidromeCredentialsStore
) {
    private suspend fun call(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        credentials: NavidromeCredentialsStore.Credentials? = credentialsStore.credentials
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = credentialsStore.buildSignedUrl(endpoint, params, credentials)
            ?: throw IOException("Subsonic server is not configured")
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                // Some servers report auth failures with an HTTP error *and* a JSON body.
                parseEnvelopeOrNull(body)?.let { return@withContext it }
                throw IOException("HTTP ${response.code} from Subsonic server")
            }
            parseEnvelopeOrNull(body) ?: throw IOException("Unexpected response from Subsonic server")
        }
    }

    private fun parseEnvelopeOrNull(body: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val envelope = root.optJSONObject("subsonic-response") ?: return null
        if (envelope.optString("status") != "ok") {
            val error = envelope.optJSONObject("error")
            throw SubsonicApiException(
                code = error?.optInt("code", 0) ?: 0,
                message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Subsonic request failed"
            )
        }
        return envelope
    }

    suspend fun ping(credentials: NavidromeCredentialsStore.Credentials): SubsonicPing {
        val envelope = call("ping.view", credentials = credentials)
        return SubsonicPing(
            type = envelope.optStringOrNull("type"),
            serverVersion = envelope.optStringOrNull("serverVersion"),
            apiVersion = envelope.optStringOrNull("version")
        )
    }

    /** Pages through every song using `search3` with an empty query (Navidrome / OpenSubsonic). */
    suspend fun searchAllSongs(offset: Int, count: Int): List<SubsonicSong> {
        val envelope = call(
            "search3.view",
            mapOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to count.toString(),
                "songOffset" to offset.toString()
            )
        )
        return envelope.optJSONObject("searchResult3")?.optJSONArray("song").toSongs()
    }

    suspend fun getAlbumList(offset: Int, size: Int): List<SubsonicAlbum> {
        val envelope = call(
            "getAlbumList2.view",
            mapOf("type" to "alphabeticalByName", "size" to size.toString(), "offset" to offset.toString())
        )
        val albums = envelope.optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
        return (0 until albums.length()).mapNotNull { index ->
            val obj = albums.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optStringOrNull("id") ?: return@mapNotNull null
            SubsonicAlbum(id, obj.optString("name", obj.optString("title")), obj.optInt("songCount"))
        }
    }

    suspend fun getAlbumSongs(albumId: String): List<SubsonicSong> {
        val envelope = call("getAlbum.view", mapOf("id" to albumId))
        return envelope.optJSONObject("album")?.optJSONArray("song").toSongs()
    }

    suspend fun getPlaylists(): List<SubsonicPlaylist> {
        val envelope = call("getPlaylists.view")
        val playlists = envelope.optJSONObject("playlists")?.optJSONArray("playlist") ?: return emptyList()
        return (0 until playlists.length()).mapNotNull { index ->
            val obj = playlists.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optStringOrNull("id") ?: return@mapNotNull null
            SubsonicPlaylist(
                id = id,
                name = obj.optString("name", "Playlist"),
                songCount = obj.optInt("songCount"),
                coverArt = obj.optStringOrNull("coverArt"),
                changedMillis = parseIsoDate(obj.optStringOrNull("changed"))
            )
        }
    }

    suspend fun getPlaylistSongs(playlistId: String): List<SubsonicSong> {
        val envelope = call("getPlaylist.view", mapOf("id" to playlistId))
        return envelope.optJSONObject("playlist")?.optJSONArray("entry").toSongs()
    }

    /** `submission=false` marks the song as "now playing"; `true` records a play. */
    suspend fun scrobble(songId: String, submission: Boolean, timeMillis: Long = System.currentTimeMillis()) {
        call(
            "scrobble.view",
            mapOf(
                "id" to songId,
                "submission" to submission.toString(),
                "time" to timeMillis.toString()
            )
        )
    }

    private fun JSONArray?.toSongs(): List<SubsonicSong> {
        if (this == null) return emptyList()
        val result = ArrayList<SubsonicSong>(length())
        for (index in 0 until length()) {
            val obj = optJSONObject(index) ?: continue
            if (obj.optBoolean("isDir", false)) continue
            if (obj.optBoolean("isVideo", false)) continue
            parseSong(obj)?.let(result::add)
        }
        return result
    }

    private fun parseSong(obj: JSONObject): SubsonicSong? {
        val id = obj.optStringOrNull("id") ?: return null
        val artist = obj.optStringOrNull("artist") ?: obj.optStringOrNull("displayArtist") ?: "Unknown Artist"
        // OpenSubsonic servers expose the individual artists; fall back to the display string.
        val artistNames = obj.optJSONArray("artists")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optStringOrNull("name") }
        }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        return SubsonicSong(
            id = id,
            title = obj.optStringOrNull("title") ?: obj.optStringOrNull("name") ?: "Unknown",
            artist = artist,
            artistNames = artistNames,
            album = obj.optStringOrNull("album") ?: "Unknown Album",
            albumId = obj.optStringOrNull("albumId") ?: obj.optStringOrNull("parent"),
            albumArtist = obj.optStringOrNull("displayAlbumArtist")
                ?: obj.optJSONArray("albumArtists")?.optJSONObject(0)?.optStringOrNull("name"),
            coverArt = obj.optStringOrNull("coverArt"),
            durationSec = obj.optInt("duration", 0),
            track = obj.optInt("track", 0),
            discNumber = obj.optInt("discNumber", 0).takeIf { it > 0 },
            year = obj.optInt("year", 0),
            genre = obj.optStringOrNull("genre"),
            bitRateKbps = obj.optInt("bitRate", 0).takeIf { it > 0 },
            contentType = obj.optStringOrNull("contentType"),
            suffix = obj.optStringOrNull("suffix"),
            path = obj.optStringOrNull("path"),
            createdMillis = parseIsoDate(obj.optStringOrNull("created"))
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun parseIsoDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
