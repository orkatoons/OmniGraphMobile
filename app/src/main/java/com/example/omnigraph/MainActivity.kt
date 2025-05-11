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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.example.omnigraph.ui.theme.LocalDarkMode
import androidx.compose.runtime.CompositionLocalProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            
            CompositionLocalProvider(LocalDarkMode provides isDarkMode) {
                OmnigraphTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        OmnigraphApp(
                            isDarkMode = isDarkMode,
                            onDarkModeChange = { isDarkMode = it }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmnigraphApp(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit
) {
    val viewModel: OmnigraphViewModel = viewModel()
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showInfoDialog by remember { mutableStateOf(false) }
    
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
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Omnigraph Codex",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { 
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 24.dp)
                        )
                    },
                    label = { Text("Gallery") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            context.startActivity(Intent(context, GalleryActivity::class.java))
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NavigationDrawerItemDefaults.ItemPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp)
                        )
                        Text("Dark Mode")
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = onDarkModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Bar
                TopAppBar(
                    title = { Text("Omnigraph Codex") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Read Me"
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
    }
    
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Omnigraph Codex") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "Converts audio data into pixel values and vice-versa to generate unique images and reconstruct sound.\n\n" +
                        "🔴 Method A – Stores different parts of the audio in Red, Green, and Blue channels sequentially.\n" +
                        "🔵 Method B – Interweaves audio data into all channels in each pixel simultaneously.\n" +
                        "🟢 Method C – Uses frequency spectrum analysis to distribute the audio across the image.\n\n" +
                        "🎨 Why I Created This\n" +
                        "Omnigraph Codex was designed for:\n" +
                        "✅ Creating visually generative, abstract, and glitch art\n" +
                        "✅ Sampling images into abstract audio pieces for music producers\n" +
                        "✅ Exploring new forms of audiovisual transformation\n\n" +
                        "✨ Tip: Different methods produce distinct visual and audio patterns! Experiment to discover new textures and sounds.\n\n" +
                        "🌎 Join the Community!\n" +
                        "I want this codex to bring together a community of artists and technical creators.\n" +
                        "If you have any interesting creations, please share them at:\n\n"
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/OmnigraphCodex/"))
                            context.startActivity(intent)
                        }
                    ) {
                        Text(
                            "📌 r/OmnigraphCodex (Reddit)",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "\nLet's build something amazing together! 🎨🎶🚀\n\n" +
                        "📌 Developed by Om Chari and GPT"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close")
                }
            }
        )
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