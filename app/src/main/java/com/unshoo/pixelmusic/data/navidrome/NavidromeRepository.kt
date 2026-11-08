package com.unshoo.pixelmusic.data.navidrome

import com.unshoo.pixelmusic.data.database.AlbumEntity
import com.unshoo.pixelmusic.data.database.ArtistEntity
import com.unshoo.pixelmusic.data.database.LocalPlaylistDao
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.PlaylistEntity
import com.unshoo.pixelmusic.data.database.SongArtistCrossRef
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.data.database.SourceType
import com.unshoo.pixelmusic.data.stream.CloudMusicUtils
import com.unshoo.pixelmusic.data.stream.CloudStreamSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subsonic / Navidrome integration: login, library + playlist sync into the unified
 * library, and helpers used by streaming and play reporting.
 *
 * Synced songs live in the regular `songs` table with `source_type = NAVIDROME` and a
 * `navidrome://{songId}` content URI, which [NavidromeStreamProxy] resolves at play time.
 */
@Singleton
class NavidromeRepository @Inject constructor(
    private val api: NavidromeApiService,
    private val credentialsStore: NavidromeCredentialsStore,
    private val musicDao: MusicDao,
    private val playlistDao: LocalPlaylistDao
) {
    sealed interface SyncState {
        data object Idle : SyncState
        data class Running(val message: String) : SyncState
        data class Done(val songCount: Int, val playlistCount: Int) : SyncState
        data class Failed(val message: String) : SyncState
    }

    data class SyncResult(val songCount: Int, val playlistCount: Int)

    companion object {
        const val PLAYLIST_SOURCE = "NAVIDROME"
        const val PLAYLIST_ID_PREFIX = "navidrome_"
        const val URI_SCHEME = "navidrome"
        private const val GENRE_FALLBACK = "Navidrome"
        private const val PARENT_DIRECTORY = "/Cloud/Navidrome"
        private const val SONG_ID_OFFSET = 9_000_000_000_000L
        private const val ALBUM_ID_OFFSET = 10_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 11_000_000_000_000L
        private const val ID_SPACE = 1_000_000_000_000L
        private const val SEARCH_PAGE_SIZE = 500
        private const val ALBUM_PAGE_SIZE = 500
        private const val COVER_ART_SIZE = "600"

        fun contentUriFor(songId: String) = "$URI_SCHEME://$songId"

        /** Stable 40-bit-ish hash so large libraries don't collide like 32-bit hashCode would. */
        private fun stableHash(value: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return Math.floorMod(hash, ID_SPACE)
        }

        fun toUnifiedSongId(navidromeId: String): Long = -(SONG_ID_OFFSET + stableHash(navidromeId))
        private fun toUnifiedAlbumId(key: String): Long = -(ALBUM_ID_OFFSET + stableHash(key.lowercase()))
        private fun toUnifiedArtistId(name: String): Long = -(ARTIST_ID_OFFSET + stableHash(name.lowercase()))
    }

    private val syncMutex = Mutex()

    private val _isLoggedIn = MutableStateFlow(credentialsStore.isConfigured)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    val isLoggedIn: Boolean get() = credentialsStore.isConfigured
    val serverUrl: String? get() = credentialsStore.credentials?.serverUrl
    val username: String? get() = credentialsStore.credentials?.username
    val serverDescription: String?
        get() = listOfNotNull(credentialsStore.serverType, credentialsStore.serverVersion)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    val lastSyncTime: Long get() = credentialsStore.lastSyncTime

    fun observeSyncedPlaylistCount(): Flow<Int> = playlistDao.observePlaylistCountBySource(PLAYLIST_SOURCE)

    fun observeSyncedSongCount(): Flow<Int> = musicDao.observeSongCountBySourceType(SourceType.NAVIDROME)

    // ─── Auth ──────────────────────────────────────────────────────────

    /**
     * Verifies the credentials against the server and stores them on success.
     * Falls back to legacy (hex-encoded password) auth for servers without token auth.
     */
    suspend fun login(rawServerUrl: String, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            val serverUrl = NavidromeCredentialsStore.normalizeServerUrl(rawServerUrl)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid server URL"))
            if (username.isBlank() || password.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Username and password are required"))
            }
            val tokenCredentials = NavidromeCredentialsStore.Credentials(
                serverUrl = serverUrl,
                username = username.trim(),
                password = password,
                useLegacyAuth = false
            )
            try {
                val (credentials, ping) = try {
                    tokenCredentials to api.ping(tokenCredentials)
                } catch (e: SubsonicApiException) {
                    if (e.code != SubsonicApiException.CODE_TOKEN_AUTH_NOT_SUPPORTED) throw e
                    val legacy = tokenCredentials.copy(useLegacyAuth = true)
                    legacy to api.ping(legacy)
                }
                credentialsStore.save(credentials, ping.type, ping.serverVersion)
                _isLoggedIn.value = true
                Result.success(credentials.username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Navidrome login failed")
                Result.failure(e)
            }
        }

    suspend fun logout() = withContext(Dispatchers.IO) {
        // Clear credentials first so an in-flight sync fails fast instead of delaying logout.
        credentialsStore.clear()
        _isLoggedIn.value = false
        syncMutex.withLock {
            _syncState.value = SyncState.Idle
            playlistDao.deletePlaylistSongsBySource(PLAYLIST_SOURCE)
            playlistDao.deletePlaylistsBySource(PLAYLIST_SOURCE)
            musicDao.clearAllNavidromeSongs()
        }
    }

    // ─── Sync ──────────────────────────────────────────────────────────

    suspend fun syncLibrary(): Result<SyncResult> = withContext(Dispatchers.IO) {
        if (!credentialsStore.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Not connected to a Subsonic server"))
        }
        if (syncMutex.isLocked) {
            return@withContext Result.failure(IllegalStateException("Sync already running"))
        }
        syncMutex.withLock {
            try {
                _syncState.value = SyncState.Running("Fetching songs…")
                val songsById = LinkedHashMap<String, SubsonicSong>()
                fetchAllSongs().forEach { songsById[it.id] = it }

                _syncState.value = SyncState.Running("Fetching playlists…")
                val playlists = runCatching { api.getPlaylists() }
                    .onFailure { Timber.w(it, "Navidrome: failed to fetch playlists") }
                    .getOrDefault(emptyList())
                val playlistSongs = LinkedHashMap<SubsonicPlaylist, List<SubsonicSong>>()
                playlists.forEach { playlist ->
                    _syncState.value = SyncState.Running("Syncing playlist: ${playlist.name}")
                    val entries = runCatching { api.getPlaylistSongs(playlist.id) }
                        .onFailure { Timber.w(it, "Navidrome: failed to fetch playlist ${playlist.id}") }
                        .getOrNull() ?: return@forEach
                    playlistSongs[playlist] = entries
                    entries.forEach { songsById.putIfAbsent(it.id, it) }
                }

                _syncState.value = SyncState.Running("Updating local library…")
                writeSongsToLibrary(songsById.values.toList())
                writePlaylists(playlistSongs)

                credentialsStore.lastSyncTime = System.currentTimeMillis()
                val result = SyncResult(songsById.size, playlistSongs.size)
                _syncState.value = SyncState.Done(result.songCount, result.playlistCount)
                Result.success(result)
            } catch (e: CancellationException) {
                _syncState.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Navidrome sync failed")
                _syncState.value = SyncState.Failed(e.message ?: "Sync failed")
                Result.failure(e)
            }
        }
    }

    private suspend fun fetchAllSongs(): List<SubsonicSong> {
        // Fast path: Navidrome & OpenSubsonic servers return the whole library for an empty search3 query.
        val viaSearch = mutableListOf<SubsonicSong>()
        val searchSupported = runCatching {
            var offset = 0
            while (true) {
                val page = api.searchAllSongs(offset, SEARCH_PAGE_SIZE)
                viaSearch += page
                if (page.size < SEARCH_PAGE_SIZE) break
                offset += page.size
                _syncState.value = SyncState.Running("Fetching songs… (${viaSearch.size})")
            }
        }.onFailure { Timber.d(it, "Navidrome: empty search3 query unsupported, falling back to albums") }.isSuccess
        if (searchSupported && viaSearch.isNotEmpty()) return viaSearch

        // Fallback: walk every album (works on all Subsonic servers, but needs one request per album).
        _syncState.value = SyncState.Running("Fetching album list…")
        val albums = mutableListOf<SubsonicAlbum>()
        var offset = 0
        while (true) {
            val page = api.getAlbumList(offset, ALBUM_PAGE_SIZE)
            albums += page
            if (page.size < ALBUM_PAGE_SIZE) break
            offset += page.size
        }
        val songs = mutableListOf<SubsonicSong>()
        albums.forEachIndexed { index, album ->
            _syncState.value = SyncState.Running("Fetching songs from ${album.name}… (${index + 1}/${albums.size})")
            // Let failures propagate: a partial result would delete the missing songs locally.
            songs += api.getAlbumSongs(album.id)
        }
        return songs
    }

    private suspend fun writeSongsToLibrary(remoteSongs: List<SubsonicSong>) {
        val existingIds = musicDao.getAllNavidromeSongIds()
        val songs = ArrayList<SongEntity>(remoteSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        remoteSongs.forEach { remote ->
            if (!CloudStreamSecurity.validateNavidromeSongId(remote.id)) {
                Timber.w("Navidrome: skipping song with unsupported id format")
                return@forEach
            }
            val songId = toUnifiedSongId(remote.id)
            val artistNames = remote.artistNames.ifEmpty { CloudMusicUtils.parseArtistNames(remote.artist) }
            val primaryArtistName = artistNames.firstOrNull() ?: "Unknown Artist"
            val primaryArtistId = toUnifiedArtistId(primaryArtistName)

            artistNames.forEachIndexed { index, name ->
                val artistId = toUnifiedArtistId(name)
                artists.putIfAbsent(artistId, ArtistEntity(id = artistId, name = name, trackCount = 0, imageUrl = null))
                crossRefs += SongArtistCrossRef(songId = songId, artistId = artistId, isPrimary = index == 0)
            }

            val albumName = remote.album.ifBlank { "Unknown Album" }
            val albumArtistName = remote.albumArtist?.takeIf { it.isNotBlank() } ?: primaryArtistName
            val albumId = toUnifiedAlbumId(remote.albumId ?: "$albumName|$albumArtistName")
            val coverUrl = coverArtUrl(remote.coverArt ?: remote.albumId)
            val dateAdded = remote.createdMillis ?: System.currentTimeMillis()

            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = albumArtistName,
                    artistId = toUnifiedArtistId(albumArtistName).also { id ->
                        artists.putIfAbsent(id, ArtistEntity(id = id, name = albumArtistName, trackCount = 0, imageUrl = null))
                    },
                    albumArtUriString = coverUrl,
                    songCount = 0,
                    dateAdded = dateAdded,
                    year = remote.year,
                    albumArtist = remote.albumArtist
                )
            )

            songs += SongEntity(
                id = songId,
                title = remote.title,
                artistName = remote.artist.ifBlank { primaryArtistName },
                artistId = primaryArtistId,
                albumArtist = remote.albumArtist,
                albumName = albumName,
                albumId = albumId,
                contentUriString = contentUriFor(remote.id),
                albumArtUriString = coverUrl,
                duration = remote.durationSec * 1000L,
                genre = remote.genre ?: GENRE_FALLBACK,
                filePath = "",
                parentDirectoryPath = PARENT_DIRECTORY,
                trackNumber = remote.track,
                discNumber = remote.discNumber,
                year = remote.year,
                dateAdded = dateAdded,
                mimeType = remote.contentType ?: "audio/mpeg",
                bitrate = remote.bitRateKbps?.times(1000),
                sampleRate = null,
                sourceType = SourceType.NAVIDROME
            )
        }

        val albumCounts = songs.groupingBy { it.albumId }.eachCount()
        val finalAlbums = albums.values.map { it.copy(songCount = albumCounts[it.id] ?: 0) }
        val currentIds = songs.mapTo(HashSet()) { it.id }
        val deletedIds = existingIds.filter { it !in currentIds }

        musicDao.incrementalSyncMusicData(
            songs = songs,
            albums = finalAlbums,
            artists = artists.values.toList(),
            crossRefs = crossRefs,
            deletedSongIds = deletedIds
        )
    }

    private suspend fun writePlaylists(playlists: Map<SubsonicPlaylist, List<SubsonicSong>>) {
        val keepIds = playlists.keys.mapTo(HashSet()) { PLAYLIST_ID_PREFIX + it.id }
        playlistDao.getPlaylistIdsBySource(PLAYLIST_SOURCE)
            .filter { it !in keepIds }
            .forEach { staleId ->
                playlistDao.clearPlaylistSongs(staleId)
                playlistDao.deletePlaylist(staleId)
            }

        playlists.forEach { (playlist, entries) ->
            val id = PLAYLIST_ID_PREFIX + playlist.id
            val existing = playlistDao.getPlaylistById(id)
            val now = System.currentTimeMillis()
            playlistDao.upsertPlaylist(
                (existing ?: PlaylistEntity(id = id, name = playlist.name, createdAt = now)).copy(
                    name = playlist.name,
                    lastModified = playlist.changedMillis ?: now,
                    coverImageUri = existing?.coverImageUri ?: coverArtUrl(playlist.coverArt),
                    source = PLAYLIST_SOURCE
                )
            )
            val songIds = entries
                .filter { CloudStreamSecurity.validateNavidromeSongId(it.id) }
                .map { toUnifiedSongId(it.id).toString() }
                .distinct()
            playlistDao.replacePlaylistSongs(id, songIds)
        }
    }

    /** Credential-free cover art URL; the image loader signs it when loading. */
    private fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return credentialsStore.buildUnsignedUrl(
            "getCoverArt.view",
            mapOf("id" to coverArtId, "size" to COVER_ART_SIZE)
        )?.toString()
    }

    // ─── Streaming & reporting ─────────────────────────────────────────

    /** Signed `stream.view` URL for the given song. Never persist the result. */
    fun buildStreamUrl(songId: String): String? {
        if (!CloudStreamSecurity.validateNavidromeSongId(songId)) return null
        return credentialsStore.buildSignedUrl("stream.view", mapOf("id" to songId), json = false)?.toString()
    }

    suspend fun reportNowPlaying(songId: String) {
        if (!credentialsStore.isConfigured) return
        runCatching { api.scrobble(songId, submission = false) }
            .onFailure { Timber.d(it, "Navidrome: now-playing report failed") }
    }

    suspend fun reportPlayed(songId: String, playedAtMillis: Long) {
        if (!credentialsStore.isConfigured) return
        runCatching { api.scrobble(songId, submission = true, timeMillis = playedAtMillis) }
            .onFailure { Timber.d(it, "Navidrome: scrobble failed") }
    }
}
