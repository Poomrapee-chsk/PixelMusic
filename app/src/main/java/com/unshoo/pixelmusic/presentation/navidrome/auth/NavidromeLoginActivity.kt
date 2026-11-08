package com.unshoo.pixelmusic.presentation.navidrome.auth

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.navidrome.NavidromeRepository
import com.unshoo.pixelmusic.data.preferences.AppFontMode
import com.unshoo.pixelmusic.data.preferences.ThemePreferencesRepository
import com.unshoo.pixelmusic.presentation.navidrome.NavidromeEvent
import com.unshoo.pixelmusic.presentation.navidrome.NavidromeLoginForm
import com.unshoo.pixelmusic.presentation.navidrome.NavidromeViewModel
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import com.unshoo.pixelmusic.ui.theme.PixelMusicTheme
import dagger.hilt.android.AndroidEntryPoint
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import javax.inject.Inject

/**
 * Entry point for the Subsonic / Navidrome integration. Shows the connection form when
 * no server is configured, and the library dashboard (sync / disconnect) otherwise.
 */
@AndroidEntryPoint
class NavidromeLoginActivity : ComponentActivity() {
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appFontMode by themePreferencesRepository.appFontModeFlow
                .collectAsStateWithLifecycle(initialValue = AppFontMode.APP_DEFAULT)
            PixelMusicTheme(useSystemFont = appFontMode == AppFontMode.SYSTEM) {
                NavidromeScreen(onClose = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavidromeScreen(
    onClose: () -> Unit,
    viewModel: NavidromeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is NavidromeEvent.LoggedIn -> context.getString(R.string.toast_welcome_user, event.username)
                is NavidromeEvent.Message -> event.text
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navidrome),
                            contentDescription = stringResource(R.string.cd_navidrome_logo),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isLoggedIn) {
                                stringResource(R.string.screen_subsonic_dashboard_title)
                            } else {
                                stringResource(R.string.auth_navidrome_title)
                            },
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = isLoggedIn,
            label = "navidrome_content",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { loggedIn ->
            if (loggedIn) {
                NavidromeDashboard(viewModel = viewModel, onDisconnected = onClose)
            } else {
                val form by viewModel.form.collectAsStateWithLifecycle()
                LoginFormContent(
                    form = form,
                    onServerUrlChange = viewModel::onServerUrlChange,
                    onUsernameChange = viewModel::onUsernameChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onConnect = viewModel::login
                )
            }
        }
    }
}

private val CardShape = AbsoluteSmoothCornerShape(24.dp, 60)

@Composable
private fun LoginFormContent(
    form: NavidromeLoginForm,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val canSubmit = !form.isConnecting && form.serverUrl.isNotBlank() &&
        form.username.isNotBlank() && form.password.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.auth_navidrome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Text(
                text = stringResource(R.string.auth_navidrome_info_card),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.auth_connection_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.auth_connection_details_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text(stringResource(R.string.auth_server_url)) },
                    placeholder = { Text(stringResource(R.string.auth_server_url_placeholder_https)) },
                    supportingText = { Text(stringResource(R.string.auth_navidrome_server_url_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    singleLine = true,
                    enabled = !form.isConnecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                if (form.serverUrl.isEmpty()) {
                    TextButton(onClick = { onServerUrlChange("https://") }) {
                        Text(stringResource(R.string.auth_prefill_https))
                    }
                }

                OutlinedTextField(
                    value = form.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.auth_username)) },
                    placeholder = { Text(stringResource(R.string.auth_username_placeholder_admin)) },
                    supportingText = { Text(stringResource(R.string.auth_navidrome_username_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !form.isConnecting,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = form.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.auth_password)) },
                    placeholder = { Text(stringResource(R.string.auth_password_placeholder)) },
                    supportingText = { Text(stringResource(R.string.auth_navidrome_password_hint)) },
                    singleLine = true,
                    enabled = !form.isConnecting,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password
                                )
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) onConnect() }),
                    modifier = Modifier.fillMaxWidth()
                )

                form.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Button(
            onClick = onConnect,
            enabled = canSubmit,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (form.isConnecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.auth_connecting))
            } else {
                Text(stringResource(R.string.auth_connect))
            }
        }

        Text(
            text = stringResource(R.string.auth_navidrome_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NavidromeDashboard(
    viewModel: NavidromeViewModel,
    onDisconnected: () -> Unit
) {
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val songCount by viewModel.songCount.collectAsStateWithLifecycle()
    val playlistCount by viewModel.playlistCount.collectAsStateWithLifecycle()
    val isSyncing = syncState is NavidromeRepository.SyncState.Running
    val lastSync = remember(syncState) { viewModel.lastSyncTime }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = viewModel.username.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = viewModel.serverUrl.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                viewModel.serverDescription?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                icon = Icons.Rounded.LibraryMusic,
                value = stringResource(R.string.dash_song_count, songCount),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Rounded.QueueMusic,
                value = stringResource(R.string.dash_playlists_synced_count, playlistCount),
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.dash_quick_actions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.dash_quick_actions_subsonic_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (val state = syncState) {
                    is NavidromeRepository.SyncState.Running -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.message, style = MaterialTheme.typography.bodySmall)
                    }
                    is NavidromeRepository.SyncState.Failed -> Text(
                        text = stringResource(R.string.error_with_message_format, state.message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    is NavidromeRepository.SyncState.Done -> Text(
                        text = stringResource(R.string.dash_status_library_sync_complete),
                        style = MaterialTheme.typography.bodySmall
                    )
                    NavidromeRepository.SyncState.Idle -> if (lastSync > 0) {
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(lastSync).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = viewModel::syncLibrary,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (isSyncing) R.string.dash_status_syncing else R.string.dash_action_sync_library
                        )
                    )
                }
                OutlinedButton(
                    onClick = {
                        viewModel.logout()
                        onDisconnected()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dash_action_disconnect))
                }
            }
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
