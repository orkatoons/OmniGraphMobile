package com.example.omnigraph

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.IOException
import kotlinx.coroutines.delay
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

data class OmnigraphState(
    val isDarkMode: Boolean = false,
    val encodingMethod: String = "A",
    val currentImage: ByteArray? = null,
    val currentAudio: ByteArray? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val sourceType: SourceType = SourceType.NONE,
    val imageData: ByteArray? = null,
    val audioData: ByteArray? = null,
    val isLoading: Boolean = false,
    val playbackPosition: Float = 0f
)

enum class SourceType {
    NONE,
    AUDIO,
    IMAGE
}

class OmnigraphViewModel : ViewModel() {
    private var audioProcessor: AudioProcessor? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioData: ByteArray? = null
    private var isPlaying = false
    private var currentPosition = 0
    private var audioDuration = 0
    private var context: Context? = null

    private val _state = MutableStateFlow(OmnigraphState())
    val state: StateFlow<OmnigraphState> = _state.asStateFlow()

    fun initialize(context: Context) {
        this.context = context
        audioProcessor = AudioProcessor(context)
    }

    fun encodeAudioToImage(audioUri: Uri) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                
                // Read audio file
                val audioBytes = context?.contentResolver?.openInputStream(audioUri)?.use { inputStream -> 
                    inputStream.readBytes() 
                } ?: throw IOException("Failed to read audio file")

                // Store the audio data
                currentAudioData = audioBytes
                _state.value = _state.value.copy(
                    currentAudio = audioBytes,
                    sourceType = SourceType.AUDIO
                )

                // Encode audio to image
                val imageBytes = audioProcessor?.encodeAudioToImage(audioBytes)
                if (imageBytes != null) {
                    _state.value = _state.value.copy(
                        currentImage = imageBytes,
                        imageData = imageBytes,
                        isLoading = false
                    )
                } else {
                    throw IOException("Failed to encode audio to image")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error occurred",
                    isLoading = false
                )
            }
        }
    }

    fun decodeImageToAudio(imageUri: Uri) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                
                // Read image file
                val imageBytes = context?.contentResolver?.openInputStream(imageUri)?.use { inputStream -> 
                    inputStream.readBytes()
                } ?: throw IOException("Failed to read image file")

                // Store the image data
                _state.value = _state.value.copy(
                    currentImage = imageBytes,
                    sourceType = SourceType.IMAGE
                )

                // Decode image to audio
                val audioData = audioProcessor?.decodeImageToAudio(imageBytes)
                if (audioData != null) {
                    // Save decoded audio for playback
                    currentAudioData = audioData
                    
                    _state.value = _state.value.copy(
                        currentAudio = audioData,
                        audioData = audioData,
                        isLoading = false
                    )
                } else {
                    throw IOException("Failed to decode image to audio")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error occurred",
                    isLoading = false
                )
            }
        }
    }

    fun togglePlayback() {
        if (currentAudioData == null) return

        if (isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        try {
            // Create temporary WAV file
            val tempFile = File.createTempFile("temp_audio", ".wav", context?.cacheDir)
            audioProcessor?.saveAudioToFile(currentAudioData!!, tempFile)

            // Initialize MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                this@OmnigraphViewModel.isPlaying = true
                audioDuration = duration
                
                // Set up completion listener
                setOnCompletionListener {
                    stopPlayback()
                }
            }

            // Start position updates
            startPositionUpdates()
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "Failed to start playback: ${e.message}"
            )
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        isPlaying = false
        currentPosition = 0
        _state.value = _state.value.copy(
            playbackPosition = 0f
        )
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (isPlaying && mediaPlayer != null) {
                delay(100)
                currentPosition = mediaPlayer?.currentPosition ?: 0
                _state.value = _state.value.copy(
                    playbackPosition = currentPosition.toFloat() / audioDuration.toFloat()
                )
            }
        }
    }

    fun seekTo(position: Float) {
        if (mediaPlayer != null) {
            val newPosition = (position * audioDuration).toInt()
            mediaPlayer?.seekTo(newPosition)
            currentPosition = newPosition
            _state.value = _state.value.copy(
                playbackPosition = position
            )
        }
    }

    fun toggleDarkMode() {
        _state.value = _state.value.copy(isDarkMode = !_state.value.isDarkMode)
    }
    
    fun setEncodingMethod(method: String) {
        _state.value = _state.value.copy(encodingMethod = method)
    }
    
    fun getCurrentProgress(): Float {
        return audioProcessor?.getCurrentProgress() ?: 0f
    }
    
    fun saveOutput(context: Context) {
        viewModelScope.launch {
            try {
                val outputDir = File(context.getExternalFilesDir(null), "OmnigraphOutput").apply {
                    if (!exists()) mkdirs()
                }
                
                val timestamp = System.currentTimeMillis()
                
                when (_state.value.sourceType) {
                    SourceType.AUDIO -> {
                        // Save the generated image
                        val outputFile = File(outputDir, "encoded_image_$timestamp.png")
                        _state.value.imageData?.let { imageData ->
                            outputFile.writeBytes(imageData)
                            _state.value = _state.value.copy(error = "Image saved to: ${outputFile.absolutePath}")
                        } ?: run {
                            _state.value = _state.value.copy(error = "No image data to save")
                        }
                    }
                    SourceType.IMAGE -> {
                        // Save the generated audio
                        val outputFile = File(outputDir, "decoded_audio_$timestamp.wav")
                        _state.value.audioData?.let { audioData ->
                            outputFile.writeBytes(audioData)
                            _state.value = _state.value.copy(error = "Audio saved to: ${outputFile.absolutePath}")
                        } ?: run {
                            _state.value = _state.value.copy(error = "No audio data to save")
                        }
                    }
                    SourceType.NONE -> {
                        _state.value = _state.value.copy(error = "No file loaded")
                    }
                }
            } catch (e: Exception) {
                Log.e("OmnigraphViewModel", "Error saving output", e)
                _state.value = _state.value.copy(error = "Error saving output: ${e.message}")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        audioProcessor?.release()
    }
} 