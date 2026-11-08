package com.unshoo.pixelmusic.presentation.navidrome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.navidrome.NavidromeRepository
import com.unshoo.pixelmusic.data.navidrome.SubsonicApiException
import com.unshoo.pixelmusic.di.AppScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

data class NavidromeLoginForm(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null
)

sealed interface NavidromeEvent {
    data class LoggedIn(val username: String) : NavidromeEvent
    data class Message(val text: String) : NavidromeEvent
}

@HiltViewModel
class NavidromeViewModel @Inject constructor(
    private val repository: NavidromeRepository,
    @AppScope private val appScope: CoroutineScope
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedInFlow
    val syncState: StateFlow<NavidromeRepository.SyncState> = repository.syncState

    val songCount: StateFlow<Int> = repository.observeSyncedSongCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val playlistCount: StateFlow<Int> = repository.observeSyncedPlaylistCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _form = MutableStateFlow(
        NavidromeLoginForm(
            serverUrl = repository.serverUrl.orEmpty(),
            username = repository.username.orEmpty()
        )
    )
    val form: StateFlow<NavidromeLoginForm> = _form.asStateFlow()

    private val _events = MutableSharedFlow<NavidromeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<NavidromeEvent> = _events.asSharedFlow()

    val serverUrl: String? get() = repository.serverUrl
    val username: String? get() = repository.username
    val serverDescription: String? get() = repository.serverDescription
    val lastSyncTime: Long get() = repository.lastSyncTime

    fun onServerUrlChange(value: String) = _form.update { copy(serverUrl = value, error = null) }
    fun onUsernameChange(value: String) = _form.update { copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _form.update { copy(password = value, error = null) }

    fun login() {
        val current = _form.value
        if (current.isConnecting) return
        _form.update { copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            repository.login(current.serverUrl, current.username, current.password)
                .onSuccess { name ->
                    _form.update { copy(isConnecting = false, password = "") }
                    _events.tryEmit(NavidromeEvent.LoggedIn(name))
                    // Pull the library right away; runs in the app scope so it survives the screen.
                    syncLibrary()
                }
                .onFailure { error ->
                    _form.update { copy(isConnecting = false, error = describeError(error)) }
                }
        }
    }

    fun syncLibrary() {
        appScope.launch {
            repository.syncLibrary()
                .onSuccess { result ->
                    _events.tryEmit(
                        NavidromeEvent.Message("Synced ${result.songCount} songs and ${result.playlistCount} playlists")
                    )
                }
                .onFailure { error ->
                    if (error !is IllegalStateException) {
                        _events.tryEmit(NavidromeEvent.Message(describeError(error)))
                    }
                }
        }
    }

    fun logout() {
        appScope.launch { repository.logout() }
    }

    private inline fun MutableStateFlow<NavidromeLoginForm>.update(block: NavidromeLoginForm.() -> NavidromeLoginForm) {
        value = value.block()
    }

    private fun describeError(error: Throwable): String = when (error) {
        is SubsonicApiException -> when (error.code) {
            SubsonicApiException.CODE_WRONG_CREDENTIALS -> "Wrong username or password"
            else -> error.message ?: "Server error (${error.code})"
        }
        is IllegalArgumentException -> error.message ?: "Invalid input"
        is UnknownHostException -> "Server not found. Check the URL."
        is SSLException -> "Secure connection failed. Check the server's HTTPS certificate."
        is IOException -> error.message?.let { "Connection failed: $it" } ?: "Connection failed"
        else -> error.message ?: "Something went wrong"
    }
}
