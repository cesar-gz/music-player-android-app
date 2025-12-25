package com.example.strawhattunes

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import com.example.strawhattunes.ui.theme.StrawHatTunesTheme

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
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
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    StrawHatTunesTheme {
        Greeting("Android")
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

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(permission)
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var nowPlaying by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // Poll playback position for a simple seek UI (good enough for a basic app).
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            songs = loadSongsFromMediaStore(context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(250)
        }
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
                Text(
                    nowPlaying?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )

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
                    Button(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("${positionMs / 1000}s / ${durationMs.coerceAtLeast(0L) / 1000}s")
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
                    supportingContent = { Text(song.artist) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            nowPlaying = song
                            val mediaItem = MediaItem.fromUri(song.uri)
                            player.setMediaItem(mediaItem)
                            player.prepare()
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
            val artist = cursor.getString(artistCol) ?: "Unknown"

            val contentUri = ContentUris.withAppendedId(collection, id)
            songs.add(Song(id, title, artist, contentUri))
        }
    }

    return songs
}