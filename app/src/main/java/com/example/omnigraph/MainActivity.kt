package com.example.omnigraph

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.omnigraph.ui.theme.OmnigraphTheme
import android.graphics.BitmapFactory
import androidx.media3.exoplayer.offline.Download
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.appcompat.widget.FloatingActionButton

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setContent {
            OmnigraphTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OmnigraphApp()
                }
            }
        }

        findViewById<FloatingActionButton>(R.id.galleryFab).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmnigraphApp() {
    val viewModel: OmnigraphViewModel = viewModel()
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    
    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }
    
    // File picker launchers
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            Log.d("MainActivity", "Selected audio URI: $uri")
            val mimeType = context.contentResolver.getType(uri)
            Log.d("MainActivity", "Audio MIME type: $mimeType")
            viewModel.processAudioFile(it, context) 
        }
    }
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            Log.d("MainActivity", "Selected image URI: $uri")
            val mimeType = context.contentResolver.getType(uri)
            Log.d("MainActivity", "Image MIME type: $mimeType")
            viewModel.processImageFile(it, context) 
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Bar
        TopAppBar(
            title = { Text("Omnigraph Codex") },
            actions = {
                IconButton(onClick = { /* TODO: Show Read Me */ }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Read Me"
                    )
                }
                IconButton(onClick = { viewModel.toggleDarkMode() }) {
                    Icon(
                        imageVector = if (state.isDarkMode) Icons.Default.Settings else Icons.Default.Info,
                        contentDescription = "Toggle Dark Mode"
                    )
                }
            }
        )
        
        // Encoding Method Selection
        var showMethodMenu by remember { mutableStateOf(false) }
        
        Box {
            Button(
                onClick = { showMethodMenu = true },
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text("Encoding Method: ${state.encodingMethod}")
            }
            
            DropdownMenu(
                expanded = showMethodMenu,
                onDismissRequest = { showMethodMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("A - Channel Multiplexing") },
                    onClick = {
                        viewModel.setEncodingMethod("A")
                        showMethodMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("B - Pixel Interleaving") },
                    onClick = {
                        viewModel.setEncodingMethod("B")
                        showMethodMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("C - Spectral Encoding") },
                    onClick = {
                        viewModel.setEncodingMethod("C")
                        showMethodMenu = false
                    }
                )
            }
        }
        
        // Image Display Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.LightGray)
        ) {
            state.currentImage?.let { imageData ->
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Encoded/Decoded Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "Error: Invalid image data",
                        color = Color.Red,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } ?: run {
                Text(
                    text = "No image loaded",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        
        // Control Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { audioPickerLauncher.launch("audio/*") }
            ) {
                Text("Choose Audio")
            }
            
            Button(
                onClick = { imagePickerLauncher.launch("image/*") }
            ) {
                Text("Choose Image")
            }
            
            Button(
                onClick = { viewModel.saveOutput(context) },
                enabled = state.currentAudio != null || state.currentImage != null
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save Output"
                )
            }
        }
        
        if (state.currentAudio != null) {
            PlaybackControls(
                viewModel = viewModel,
                isPlaying = state.isPlaying,
                progress = state.progress,
                onPlayPause = { viewModel.togglePlayback() },
                onSeek = { viewModel.seekTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Error Message
        state.error?.let { error ->
            Text(
                text = error,
                color = Color.Red,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun PlaybackControls(
    viewModel: OmnigraphViewModel,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(100)
            onSeek(viewModel.getCurrentProgress())
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }

        Slider(
            value = progress,
            onValueChange = onSeek,
            modifier = Modifier.weight(1f)
        )
    }
}