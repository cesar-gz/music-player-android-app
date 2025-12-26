package com.example.strawhattunes

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.strawhattunes.ui.theme.StrawHatTunesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val uri: android.net.Uri
)

enum class ViewMode { LIBRARY, PLAYLISTS, PLAYLIST_DETAIL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrawHatTunesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) {
                        SimpleMusicPlayerScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimpleMusicPlayerScreen() {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val dao = remember { db.playlistDao() }
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var removeFromPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.LIBRARY) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistDetailSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var queueSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentQueuePlaylistId by remember { mutableStateOf<Long?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val setQueue = { newQueue: List<Song>, startIndex: Int?, autoPlay: Boolean ->
        queueSongs = newQueue

        val items = newQueue.map { MediaItem.fromUri(it.uri) }
        player.setMediaItems(items)
        player.prepare()

        if (startIndex != null) {
            player.seekTo(startIndex, 0)
        }
        if (autoPlay) player.play()
    }


    val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var nowPlaying by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(permission)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            songs = loadSongsFromMediaStore(context)
        }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(songs) {
        if (songs.isNotEmpty() && queueSongs.isEmpty()) {
            setQueue(songs, null, false)
            currentQueuePlaylistId = null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            val index = player.currentMediaItemIndex
            if (index >= 0 && index < queueSongs.size) {
                nowPlaying = queueSongs[index]
            }

            delay(250)
        }
    }

    LaunchedEffect(queueSongs) {
        if (queueSongs.isNotEmpty() && nowPlaying == null) {
            nowPlaying = queueSongs[0]
        }
    }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) { dao.getPlaylists() }
    }

    LaunchedEffect(selectedPlaylist?.playlistId) {
        val pl = selectedPlaylist ?: return@LaunchedEffect

        val rows = withContext(Dispatchers.IO) { dao.getSongsInPlaylist(pl.playlistId) }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        playlistDetailSongs = rows.map {
            Song(
                id = it.mediaStoreId,
                title = it.title,
                artist = it.artist,
                uri = ContentUris.withAppendedId(collection, it.mediaStoreId)
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("StrawHatTunes", style = MaterialTheme.typography.headlineSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = {
                viewMode = ViewMode.LIBRARY
                if (songs.isNotEmpty()) setQueue(songs, null, false)
            }) { Text("Library") }
            TextButton(onClick = { viewMode = ViewMode.PLAYLISTS }) { Text("Playlists") }

            if (viewMode == ViewMode.PLAYLIST_DETAIL) {
                TextButton(onClick = {
                    viewMode = ViewMode.PLAYLISTS
                    selectedPlaylist = null
                }) { Text("Back") }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (!hasPermission) {
            Text("Permission is required to list and play your device audio.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(permission) }) {
                Text("Grant permission")
            }
            return@Column
        }

        // Now Playing
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    nowPlaying?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleMedium
                )
                nowPlaying?.artist?.let { artist ->
                    Text(artist, style = MaterialTheme.typography.bodyMedium)
                }


                Spacer(Modifier.height(8.dp))

                // Seek bar
                val safeDuration = durationMs.takeIf { it > 0 } ?: 1L
                Slider(
                    value = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        val newPos = (safeDuration * frac).toLong()
                        player.seekTo(newPos)
                    }
                )

                Row {
                    val restartThresholdMs = 3000L

                    IconButton(
                        onClick = {
                            if (player.currentPosition > restartThresholdMs) player.seekTo(0)
                            else player.seekToPreviousMediaItem()
                        },
                        enabled = player.hasPreviousMediaItem() || player.currentPosition > restartThresholdMs
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }

                    Button(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }

                    IconButton(
                        onClick = { player.seekToNextMediaItem() },
                        enabled = player.hasNextMediaItem()
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("${formatMs(positionMs)} / ${formatMs(durationMs)}")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (viewMode) {
            ViewMode.LIBRARY -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Library", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showCreatePlaylistDialog = true }) {
                        Text("New playlist")
                    }
                }

                // Create playlist dialog
                if (showCreatePlaylistDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylistDialog = false },
                        title = { Text("Create playlist") },
                        text = {
                            OutlinedTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it },
                                label = { Text("Playlist name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val name = newPlaylistName.trim()
                                    if (name.isNotEmpty()) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                dao.insertPlaylist(PlaylistEntity(name = name))
                                            }
                                            playlists =
                                                withContext(Dispatchers.IO) { dao.getPlaylists() }
                                        }
                                        newPlaylistName = ""
                                        showCreatePlaylistDialog = false
                                    }
                                }
                            ) { Text("Create") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showCreatePlaylistDialog = false
                                newPlaylistName = ""
                            }) { Text("Cancel") }
                        }
                    )
                }

                // Add to playlist dialog (long-press)
                addToPlaylistSong?.let { selectedSong ->
                    AlertDialog(
                        onDismissRequest = { addToPlaylistSong = null },
                        title = { Text("Add to playlist") },
                        text = {
                            Column {
                                if (playlists.isEmpty()) {
                                    Text("No playlists yet. Create one first.")
                                } else {
                                    playlists.forEach { pl ->
                                        Text(
                                            text = pl.name,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            dao.addSongToPlaylist(
                                                                PlaylistSongEntity(
                                                                    playlistId = pl.playlistId,
                                                                    mediaStoreId = selectedSong.id,
                                                                    title = selectedSong.title,
                                                                    artist = selectedSong.artist
                                                                )
                                                            )
                                                        }
                                                        addToPlaylistSong = null
                                                    }
                                                }
                                                .padding(vertical = 10.dp)
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { addToPlaylistSong = null }) { Text("Close") }
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize()) {
                    items(songs) { song ->
                        ListItem(
                            headlineContent = { Text(song.title) },
                            supportingContent = { song.artist?.let { Text(it) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        val index = songs.indexOf(song)
                                        nowPlaying = song
                                        setQueue(songs, index, true)
                                    },
                                    onLongClick = {
                                        addToPlaylistSong = song
                                    }
                                )
                        )
                        HorizontalDivider()
                    }
                }
            }

            ViewMode.PLAYLISTS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playlists", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showCreatePlaylistDialog = true }) {
                        Text("New playlist")
                    }
                }

                // Reuse create playlist dialog here too
                if (showCreatePlaylistDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylistDialog = false },
                        title = { Text("Create playlist") },
                        text = {
                            OutlinedTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it },
                                label = { Text("Playlist name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val name = newPlaylistName.trim()
                                    if (name.isNotEmpty()) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                dao.insertPlaylist(PlaylistEntity(name = name))
                                            }
                                            playlists =
                                                withContext(Dispatchers.IO) { dao.getPlaylists() }
                                        }
                                        newPlaylistName = ""
                                        showCreatePlaylistDialog = false
                                    }
                                }
                            ) { Text("Create") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showCreatePlaylistDialog = false
                                newPlaylistName = ""
                            }) { Text("Cancel") }
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlists) { pl ->
                        ListItem(
                            headlineContent = { Text(pl.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPlaylist = pl
                                    viewMode = ViewMode.PLAYLIST_DETAIL
                                }
                        )
                        HorizontalDivider()
                    }
                }
            }

            ViewMode.PLAYLIST_DETAIL -> {
                val pl = selectedPlaylist

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(pl?.name ?: "Playlist", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { viewMode = ViewMode.PLAYLISTS; selectedPlaylist = null },
                    ) { Text("Back") }
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        if (playlistDetailSongs.isNotEmpty()) {
                            setQueue(playlistDetailSongs, 0, true)
                        }
                    },
                    enabled = playlistDetailSongs.isNotEmpty()
                ) { Text("Play all") }

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlistDetailSongs) { song ->
                        ListItem(
                            headlineContent = { Text(song.title) },
                            supportingContent = { song.artist?.let { Text(it) } },
                            trailingContent = {
                                IconButton(onClick = { removeFromPlaylistSong = song }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Remove from playlist"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val index = playlistDetailSongs.indexOf(song)
                                    nowPlaying = song
                                    setQueue(playlistDetailSongs, index, true)
                                    currentQueuePlaylistId = selectedPlaylist?.playlistId
                                }
                        )
                        HorizontalDivider()
                    }
                }

                val plEntity = selectedPlaylist

                removeFromPlaylistSong?.let { songToRemove ->
                    AlertDialog(
                        onDismissRequest = { removeFromPlaylistSong = null },
                        title = { Text("Remove from playlist?") },
                        text = { Text("Remove \"${songToRemove.title}\" from this playlist?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val playlistId = plEntity?.playlistId
                                    if (playlistId == null) {
                                        removeFromPlaylistSong = null
                                        return@TextButton
                                    }

                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            dao.removeSongFromPlaylist(playlistId, songToRemove.id)
                                        }

                                        val rows = withContext(Dispatchers.IO) { dao.getSongsInPlaylist(playlistId) }
                                        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                        val updated = rows.map {
                                            Song(
                                                id = it.mediaStoreId,
                                                title = it.title,
                                                artist = it.artist,
                                                uri = ContentUris.withAppendedId(collection, it.mediaStoreId)
                                            )
                                        }
                                        playlistDetailSongs = updated

                                        if (currentQueuePlaylistId == playlistId) {
                                            val wasPlaying = player.isPlaying
                                            setQueue(updated, null, wasPlaying)
                                        }

                                        removeFromPlaylistSong = null
                                    }
                                }
                            ) { Text("Remove") }
                        },
                        dismissButton = {
                            TextButton(onClick = { removeFromPlaylistSong = null }) { Text("Cancel") }
                        }
                    )
                }

            }
        }
    }
}

fun loadSongsFromMediaStore(context: android.content.Context): List<Song> {
    val songs = mutableListOf<Song>()

    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC}=1"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    context.contentResolver.query(
        collection,
        projection,
        selection,
        null,
        sortOrder
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val title = cursor.getString(titleCol) ?: "Unknown"
            val artist = normalizeArtist(cursor.getString(artistCol))

            val contentUri = ContentUris.withAppendedId(collection, id)
            songs.add(Song(id, title, artist, contentUri))
        }
    }

    return songs
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun normalizeArtist(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isBlank()) return null
    if (v.equals("<unknown>", ignoreCase = true)) return null
    if (v.equals(MediaStore.UNKNOWN_STRING, ignoreCase = true)) return null // some devices use this
    return v
}
