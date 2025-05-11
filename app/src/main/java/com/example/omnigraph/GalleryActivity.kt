package com.example.omnigraph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import java.io.File
import com.example.omnigraph.ui.theme.OmnigraphTheme
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.omnigraph.ui.theme.LocalDarkMode

class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) } // Set dark mode as default
            
            CompositionLocalProvider(LocalDarkMode provides isDarkMode) {
                OmnigraphTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        GalleryScreen(
                            onBackClick = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val outputDir = File(context.getExternalFilesDir(null), "OmnigraphOutput")
    val mediaFiles = remember {
        mutableStateListOf<File>().apply {
            if (outputDir.exists()) {
                addAll(outputDir.listFiles()?.filter { 
                    it.name.endsWith(".png") || it.name.endsWith(".wav") 
                } ?: emptyList())
            }
        }
    }
    var selectedMedia by remember { mutableStateOf<File?>(null) }

    // Handle back press
    DisposableEffect(activity) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedMedia != null) {
                    selectedMedia = null
                } else {
                    onBackClick()
                }
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose {
            callback.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Media") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Back to Home"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selectedMedia != null) {
            MediaViewer(
                file = selectedMedia!!,
                onBackClick = { selectedMedia = null }
            )
        } else if (mediaFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No saved media found")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaFiles) { file ->
                    MediaItem(
                        file = file,
                        onMediaClick = { selectedMedia = file }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaItem(
    file: File,
    onMediaClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable { onMediaClick() }
    ) {
        if (file.name.endsWith(".png")) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Saved image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            // For audio files, show a placeholder with play icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play audio",
                        modifier = Modifier.size(48.dp)
                    )
                    Text("Audio: ${file.name}")
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Media") },
            text = { Text("Are you sure you want to delete this file?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        file.delete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewer(
    file: File,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            if (file.name.endsWith(".wav")) {
                setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                prepare()
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    TextButton(
                        onClick = onBackClick,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Gallery"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back to Gallery")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (file.name.endsWith(".png")) {
                // Image viewer
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Saved image",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                // Audio player
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier
                                .size(64.dp)
                                .clickable {
                                    if (isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                    }
                                    isPlaying = !isPlaying
                                }
                        )
                        
                        Slider(
                            value = progress,
                            onValueChange = { newValue ->
                                progress = newValue
                                exoPlayer.seekTo((newValue * exoPlayer.duration).toLong())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
    
    // Update progress for audio playback
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(100)
            progress = (exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat())
                .coerceIn(0f, 1f)
        }
    }
} 