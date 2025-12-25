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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.strawhattunes.ui.theme.StrawHatTunesTheme
import kotlinx.coroutines.delay

data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val uri: android.net.Uri
)

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



@Composable
fun SimpleMusicPlayerScreen() {
    val context = LocalContext.current

    // Media3 player instance tied to this composable lifecycle.
    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
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
    val mediaItems = remember(songs) { songs.map { MediaItem.fromUri(it.uri) } }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(permission)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            songs = loadSongsFromMediaStore(context)
        }
    }

    // Poll playback position for a simple seek UI (good enough for a basic app).
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    LaunchedEffect(hasPermission, mediaItems) {
        if (hasPermission && mediaItems.isNotEmpty() && player.mediaItemCount == 0) {
            player.setMediaItems(mediaItems)
            player.prepare()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            val index = player.currentMediaItemIndex
            if (index >= 0 && index < songs.size) {
                nowPlaying = songs[index]
            }

            delay(250)
        }
    }

    LaunchedEffect(songs) {
        if (songs.isNotEmpty() && nowPlaying == null) nowPlaying = songs[0]
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Simple Player", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

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
                            else player.seekToPreviousMediaItem() },
                        enabled = player.hasPreviousMediaItem() || player.currentPosition > restartThresholdMs
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }

                    Button(onClick = { if (player.isPlaying) player.pause() else player.play() },
                        enabled = player.mediaItemCount > 0) {
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

        Text("Library", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(songs) { song ->
                ListItem(
                    headlineContent = { Text(song.title) },
                    supportingContent = { song.artist?.let { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val index = songs.indexOf(song)
                            nowPlaying = song
                            player.seekTo(index, 0)
                            player.play()
                        }
                )
                Divider()
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
