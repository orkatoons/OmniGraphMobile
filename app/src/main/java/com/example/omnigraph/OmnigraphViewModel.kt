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

data class OmnigraphState(
    val isDarkMode: Boolean = false,
    val encodingMethod: String = "A",
    val currentImage: ByteArray? = null,
    val currentAudio: ByteArray? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val sourceType: SourceType = SourceType.NONE
)

enum class SourceType {
    NONE,
    AUDIO,
    IMAGE
}

class OmnigraphViewModel : ViewModel() {
    private var audioProcessor: AudioProcessor? = null
    private val _state = MutableStateFlow(OmnigraphState())
    val state: StateFlow<OmnigraphState> = _state.asStateFlow()
    
    init {
        // Start progress updates when playing
        viewModelScope.launch {
            while (true) {
                if (_state.value.isPlaying) {
                    val progress = audioProcessor?.getCurrentProgress() ?: 0f
                    _state.value = _state.value.copy(progress = progress)
                }
                delay(100) // Update every 100ms
            }
        }
    }
    
    fun initialize(context: Context) {
        audioProcessor = AudioProcessor(context)
    }
    
    fun toggleDarkMode() {
        _state.value = _state.value.copy(isDarkMode = !_state.value.isDarkMode)
    }
    
    fun setEncodingMethod(method: String) {
        _state.value = _state.value.copy(encodingMethod = method)
    }
    
    fun processAudioFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val audioData = inputStream?.readBytes()
                inputStream?.close()

                if (audioData == null || audioData.isEmpty()) {
                    _state.value = _state.value.copy(error = "Failed to read audio file")
                    return@launch
                }

                val imageData = audioProcessor?.encodeAudioToImage(audioData, _state.value.encodingMethod)
                    ?: throw IllegalStateException("AudioProcessor not initialized")

                _state.value = _state.value.copy(
                    currentImage = imageData,
                    currentAudio = audioData,
                    error = null,
                    sourceType = SourceType.AUDIO
                )
                
                // Start playing the audio
                audioProcessor?.playAudio(audioData)
                _state.value = _state.value.copy(isPlaying = true)
            } catch (e: Exception) {
                Log.e("OmnigraphViewModel", "Error processing audio file", e)
                _state.value = _state.value.copy(error = "Error processing audio: ${e.message}")
            }
        }
    }
    
    fun processImageFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val imageData = inputStream?.readBytes()
                inputStream?.close()

                if (imageData == null || imageData.isEmpty()) {
                    _state.value = _state.value.copy(error = "Failed to read image file")
                    return@launch
                }

                val audioData = audioProcessor?.decodeImageToAudio(imageData, _state.value.encodingMethod)
                    ?: throw IllegalStateException("AudioProcessor not initialized")

                _state.value = _state.value.copy(
                    currentImage = imageData,
                    currentAudio = audioData,
                    error = null,
                    sourceType = SourceType.IMAGE
                )
                
                // Start playing the decoded audio
                audioProcessor?.playAudio(audioData)
                _state.value = _state.value.copy(isPlaying = true)
            } catch (e: Exception) {
                Log.e("OmnigraphViewModel", "Error processing image file", e)
                _state.value = _state.value.copy(error = "Error processing image: ${e.message}")
            }
        }
    }
    
    fun togglePlayback() {
        if (_state.value.isPlaying) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }
    
    fun pausePlayback() {
        audioProcessor?.pausePlayback()
        _state.value = _state.value.copy(isPlaying = false)
    }
    
    fun resumePlayback() {
        audioProcessor?.resumePlayback()
        _state.value = _state.value.copy(isPlaying = true)
    }
    
    fun seekTo(position: Float) {
        audioProcessor?.seekTo(position)
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
                        _state.value.currentImage?.let { imageData ->
                            outputFile.writeBytes(imageData)
                            _state.value = _state.value.copy(error = "Image saved to: ${outputFile.absolutePath}")
                        } ?: run {
                            _state.value = _state.value.copy(error = "No image data to save")
                        }
                    }
                    SourceType.IMAGE -> {
                        // Save the generated audio
                        val outputFile = File(outputDir, "decoded_audio_$timestamp.wav")
                        _state.value.currentAudio?.let { audioData ->
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
        audioProcessor?.release()
    }
} 