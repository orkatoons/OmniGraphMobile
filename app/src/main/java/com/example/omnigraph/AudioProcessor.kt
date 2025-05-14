package com.example.omnigraph

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class AudioProcessor(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying: Boolean = false
    private var currentPosition: Int = 0
    private var duration: Int = 0
    private var progressUpdateJob: kotlinx.coroutines.Job? = null
    private var lastUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val UPDATE_INTERVAL = 16L // ~60fps for smoother updates
    }

    suspend fun encodeAudioToImage(audioData: ByteArray, method: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            Log.d("AudioProcessor", "Starting audio encoding with method: $method")
            Log.d("AudioProcessor", "Input audio data size: ${audioData.size} bytes")

            if (audioData.isEmpty()) {
                throw IllegalArgumentException("Input audio data is empty")
            }

            // Convert byte array to short array manually
            val audioShorts = ShortArray(audioData.size / 2)
            for (i in audioShorts.indices) {
                val byteIndex = i * 2
                audioShorts[i] = (audioData[byteIndex].toInt() and 0xFF or 
                                (audioData[byteIndex + 1].toInt() and 0xFF shl 8)).toShort()
            }
            
            Log.d("AudioProcessor", "Converted to shorts array, size: ${audioShorts.size}")

            val audio8Bit = audioShorts.map { ((it.toFloat() + 32768) / 65535 * 255).toInt().toByte() }.toByteArray()
            Log.d("AudioProcessor", "Converted to 8-bit audio, size: ${audio8Bit.size}")

            val rgbArray = when (method) {
                "A" -> {
                    Log.d("AudioProcessor", "Using encoding method A")
                    encodeMethodA(audio8Bit)
                }
                "B" -> {
                    Log.d("AudioProcessor", "Using encoding method B")
                    encodeMethodB(audio8Bit)
                }
                "C" -> {
                    Log.d("AudioProcessor", "Using encoding method C")
                    encodeMethodC(audioShorts)
                }
                else -> throw IllegalArgumentException("Invalid encoding method: $method")
            }
            
            Log.d("AudioProcessor", "Generated RGB array, size: ${rgbArray.size}")

            // Convert RGB array to Bitmap
            val width = sqrt(rgbArray.size / 3.0).toInt()
            val height = width
            
            Log.d("AudioProcessor", "Creating bitmap with dimensions: ${width}x${height}")
            
            if (width <= 0 || height <= 0) {
                throw IllegalStateException("Invalid bitmap dimensions: ${width}x${height}")
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Log.d("AudioProcessor", "Setting bitmap pixels")
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = (y * width + x) * 3
                    if (index + 2 < rgbArray.size) {
                        val r = rgbArray[index].toInt() and 0xFF
                        val g = rgbArray[index + 1].toInt() and 0xFF
                        val b = rgbArray[index + 2].toInt() and 0xFF
                        bitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                    }
                }
            }

            // Convert Bitmap to PNG
            Log.d("AudioProcessor", "Converting bitmap to PNG")
            val outputStream = ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            bitmap.recycle()
            
            if (!success) {
                throw IllegalStateException("Failed to compress bitmap to PNG")
            }

            val result = outputStream.toByteArray()
            Log.d("AudioProcessor", "Successfully encoded audio to image, output size: ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error encoding audio to image", e)
            Log.e("AudioProcessor", "Error details: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    suspend fun decodeImageToAudio(imageData: ByteArray, method: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            // Decode PNG to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                ?: throw IllegalStateException("Failed to decode image data")

            // Extract RGB data from Bitmap
            val width = bitmap.width
            val height = bitmap.height
            val rgbData = ByteArray(width * height * 3)
            
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val index = (y * width + x) * 3
                    rgbData[index] = android.graphics.Color.red(pixel).toByte()
                    rgbData[index + 1] = android.graphics.Color.green(pixel).toByte()
                    rgbData[index + 2] = android.graphics.Color.blue(pixel).toByte()
                }
            }
            bitmap.recycle()
            
            // Convert RGB data back to audio based on method
            val audioData = when (method) {
                "A" -> decodeMethodA(rgbData)
                "B" -> decodeMethodB(rgbData)
                "C" -> decodeMethodC(rgbData)
                else -> throw IllegalArgumentException("Invalid decoding method")
            }

            // Convert to WAV format
            val outputStream = ByteArrayOutputStream()
            writeWavHeaderToStream(outputStream, audioData.size)
            outputStream.write(audioData)
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error decoding image to audio", e)
            throw e
        }
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        // Write length
        val length = data.size
        output.write((length shr 24) and 0xFF)
        output.write((length shr 16) and 0xFF)
        output.write((length shr 8) and 0xFF)
        output.write(length and 0xFF)

        // Write type
        output.write(type.toByteArray())

        // Write data
        output.write(data)

        // Write CRC (simplified for this example)
        output.write(ByteArray(4))
    }

    private fun decodeMethodA(rgbData: ByteArray): ByteArray {
        try {
            val total = rgbData.size / 3
            val side = sqrt(total.toDouble()).toInt()
            val required = side * side
            
            Log.d("AudioProcessor", "Decoding Method A: Total pixels: $total, Side: $side, Required: $required")
            
            // Extract each channel
            val redChannel = ByteArray(required)
            val greenChannel = ByteArray(required)
            val blueChannel = ByteArray(required)
            
            // Extract RGB channels
            for (i in 0 until required) {
                redChannel[i] = rgbData[i * 3]
                greenChannel[i] = rgbData[i * 3 + 1]
                blueChannel[i] = rgbData[i * 3 + 2]
            }
            
            // Calculate actual audio size (removing padding)
            val actualAudioSize = (total * 2) / 3
            val samplesPerChannel = actualAudioSize / 3
            
            Log.d("AudioProcessor", "Decoding Method A: Actual audio size: $actualAudioSize, Samples per channel: $samplesPerChannel")
            
            // Reconstruct the audio data
            val audioData = ByteArray(actualAudioSize)
            var writeIndex = 0
            
            // Write red channel
            for (i in 0 until samplesPerChannel) {
                if (i < redChannel.size) {
                    audioData[writeIndex++] = redChannel[i]
                }
            }
            
            // Write green channel
            for (i in 0 until samplesPerChannel) {
                if (i < greenChannel.size) {
                    audioData[writeIndex++] = greenChannel[i]
                }
            }
            
            // Write blue channel
            for (i in 0 until samplesPerChannel) {
                if (i < blueChannel.size) {
                    audioData[writeIndex++] = blueChannel[i]
                }
            }
            
            // Convert to 16-bit audio
            val audio16Bit = ByteArray(actualAudioSize * 2)
            for (i in 0 until actualAudioSize) {
                val sample = audioData[i].toInt() and 0xFF
                // Convert to 16-bit with proper scaling
                val scaledSample = ((sample - 128) * 256).toShort()
                audio16Bit[i * 2] = (scaledSample.toInt() and 0xFF).toByte()
                audio16Bit[i * 2 + 1] = (scaledSample.toInt() shr 8).toByte()
            }
            
            Log.d("AudioProcessor", "Decoding Method A: Final audio size: ${audio16Bit.size}")
            return audio16Bit
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error in decodeMethodA", e)
            throw e
        }
    }

    private fun decodeMethodB(rgbData: ByteArray): ByteArray {
        val total = rgbData.size / 3
        val audioData = ByteArray(total)
        
        for (i in 0 until total) {
            audioData[i] = rgbData[i * 3 + 1] // Use green channel
        }
        
        return audioData
    }

    private fun decodeMethodC(rgbData: ByteArray): ByteArray {
        val total = rgbData.size / 3
        val audioData = ByteArray(total)
        
        for (i in 0 until total) {
            audioData[i] = rgbData[i * 3 + 2] // Use blue channel
        }
        
        return audioData
    }

    private fun encodeMethodA(audio8Bit: ByteArray): ByteArray {
        try {
            val total = audio8Bit.size
            Log.d("AudioProcessor", "Method A: Processing ${total} bytes")
            
            // Calculate the size needed for a square image
            val side = ceil(sqrt(total.toDouble())).toInt()
            val required = side * side
            
            Log.d("AudioProcessor", "Method A: Creating ${side}x${side} image")
            
            // Split audio into three equal parts
            val partSize = total / 3
            val red = audio8Bit.copyOfRange(0, partSize)
            val green = audio8Bit.copyOfRange(partSize, 2 * partSize)
            val blue = audio8Bit.copyOfRange(2 * partSize, total)
            
            // Create the RGB array with proper normalization
            return ByteArray(required * 3).apply {
                // Fill each channel with its corresponding audio data
                for (i in 0 until required) {
                    // Red channel with proper normalization
                    this[i * 3] = if (i < red.size) {
                        val sample = red[i].toInt() and 0xFF
                        (sample * 255 / 256).toByte()
                    } else 0
                    
                    // Green channel with proper normalization
                    this[i * 3 + 1] = if (i < green.size) {
                        val sample = green[i].toInt() and 0xFF
                        (sample * 255 / 256).toByte()
                    } else 0
                    
                    // Blue channel with proper normalization
                    this[i * 3 + 2] = if (i < blue.size) {
                        val sample = blue[i].toInt() and 0xFF
                        (sample * 255 / 256).toByte()
                    } else 0
                }
            }
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error in encodeMethodA", e)
            throw e
        }
    }

    private fun encodeMethodB(audio8Bit: ByteArray): ByteArray {
        try {
            val total = audio8Bit.size - (audio8Bit.size % 3)
            Log.d("AudioProcessor", "Method B: Processing ${total} bytes")
            
            val side = ceil(sqrt(total / 3.0)).toInt()
            val required = side * side * 3
            
            Log.d("AudioProcessor", "Method B: Creating ${side}x${side} image")
            
            return ByteArray(required).apply {
                for (i in 0 until total step 3) {
                    val pixelIndex = (i / 3) * 3
                    this[pixelIndex] = audio8Bit[i]
                    this[pixelIndex + 1] = audio8Bit[i + 2]
                    this[pixelIndex + 2] = audio8Bit[i + 1]
                }
            }
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error in encodeMethodB", e)
            throw e
        }
    }

    private fun encodeMethodC(audioShorts: ShortArray): ByteArray {
        try {
            Log.d("AudioProcessor", "Method C: Processing ${audioShorts.size} samples")
            
            // Convert 16-bit audio to 8-bit with proper normalization
            val audio8Bit = audioShorts.map { sample ->
                // Normalize to prevent clipping
                val normalizedSample = (sample.toInt() * 127 / 32768).toInt()
                (normalizedSample + 128).toByte()
            }.toByteArray()
            
            // Calculate the size needed for a square image
            val total = audio8Bit.size
            val side = ceil(sqrt(total.toDouble())).toInt()
            val required = side * side
            
            Log.d("AudioProcessor", "Method C: Creating ${side}x${side} image")
            
            // Create the RGB array
            return ByteArray(required * 3).apply {
                // Store the entire audio data in the red channel with proper normalization
                for (i in 0 until required) {
                    // Red channel contains the audio data
                    this[i * 3] = if (i < audio8Bit.size) {
                        val sample = audio8Bit[i].toInt() and 0xFF
                        (sample * 255 / 256).toByte()
                    } else 0
                    // Green and blue channels are set to 0
                    this[i * 3 + 1] = 0
                    this[i * 3 + 2] = 0
                }
            }
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error in encodeMethodC", e)
            throw e
        }
    }

    fun saveAudioToFile(audioData: ByteArray, outputFile: File) {
        FileOutputStream(outputFile).use { output ->
            // Write WAV header
            writeWavHeader(output, audioData.size)
            // Write audio data
            output.write(audioData)
        }
    }

    private fun writeWavHeader(output: FileOutputStream, dataSize: Int) {
        try {
            Log.d("AudioProcessor", "Writing WAV header for data size: $dataSize bytes")
            
            // "RIFF" chunk descriptor
            output.write("RIFF".toByteArray())
            
            // File size
            val fileSize = dataSize + 36
            writeIntToFile(output, fileSize)
            
            // "WAVE" format
            output.write("WAVE".toByteArray())
            
            // "fmt " sub-chunk
            output.write("fmt ".toByteArray())
            
            // Sub-chunk size
            writeIntToFile(output, 16)
            
            // Audio format (1 for PCM)
            writeShortToFile(output, 1)
            
            // Number of channels
            writeShortToFile(output, 1)
            
            // Sample rate
            writeIntToFile(output, SAMPLE_RATE)
            
            // Byte rate
            val byteRate = SAMPLE_RATE * 2
            writeIntToFile(output, byteRate)
            
            // Block align
            writeShortToFile(output, 2)
            
            // Bits per sample
            writeShortToFile(output, 16)
            
            // "data" sub-chunk
            output.write("data".toByteArray())
            
            // Sub-chunk size
            writeIntToFile(output, dataSize)
            
            Log.d("AudioProcessor", "WAV header written successfully")
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error writing WAV header", e)
            throw e
        }
    }

    private fun writeIntToFile(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeShortToFile(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun writeWavHeaderToStream(output: ByteArrayOutputStream, dataSize: Int) {
        // "RIFF" chunk descriptor
        output.write("RIFF".toByteArray())
        
        // File size
        val fileSize = dataSize + 36
        writeInt(output, fileSize)
        
        // "WAVE" format
        output.write("WAVE".toByteArray())
        
        // "fmt " sub-chunk
        output.write("fmt ".toByteArray())
        
        // Sub-chunk size
        writeInt(output, 16)
        
        // Audio format (1 for PCM)
        writeShort(output, 1)
        
        // Number of channels
        writeShort(output, 1)
        
        // Sample rate
        writeInt(output, SAMPLE_RATE)
        
        // Byte rate
        val byteRate = SAMPLE_RATE * 2
        writeInt(output, byteRate)
        
        // Block align
        writeShort(output, 2)
        
        // Bits per sample
        writeShort(output, 16)
        
        // "data" sub-chunk
        output.write("data".toByteArray())
        
        // Sub-chunk size
        writeInt(output, dataSize)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    fun playAudio(audioData: ByteArray) {
        try {
            Log.d("AudioProcessor", "Starting audio playback with data size: ${audioData.size} bytes")
            
            mediaPlayer?.release()
            mediaPlayer = null
            
            val tempFile = File(context.cacheDir, "temp_audio.wav")
            Log.d("AudioProcessor", "Creating temporary WAV file at: ${tempFile.absolutePath}")
            
            FileOutputStream(tempFile).use { output ->
                writeWavHeader(output, audioData.size)
                output.write(audioData)
            }
            
            Log.d("AudioProcessor", "WAV file created successfully")
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.path)
                setOnCompletionListener { _ ->
                    Log.d("AudioProcessor", "Playback completed")
                    this@AudioProcessor.isPlaying = false
                    this@AudioProcessor.currentPosition = 0
                    progressUpdateJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioProcessor", "MediaPlayer error: what=$what, extra=$extra")
                    this@AudioProcessor.isPlaying = false
                    this@AudioProcessor.currentPosition = 0
                    progressUpdateJob?.cancel()
                    true
                }
                prepare()
            }
            
            Log.d("AudioProcessor", "MediaPlayer prepared successfully")
            
            duration = mediaPlayer?.duration ?: 0
            Log.d("AudioProcessor", "Audio duration: $duration ms")
            
            mediaPlayer?.start()
            isPlaying = true
            currentPosition = 0
            lastUpdateTime = System.currentTimeMillis()
            
            // Start position updates
            startPositionUpdates()
            
            Log.d("AudioProcessor", "Playback started")
            tempFile.delete()
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error playing audio", e)
            Log.e("AudioProcessor", "Error details: ${e.message}")
            e.printStackTrace()
            isPlaying = false
            currentPosition = 0
            duration = 0
            progressUpdateJob?.cancel()
        }
    }
    
    private fun startPositionUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = GlobalScope.launch(Dispatchers.Main) {
            var shouldContinue = true
            while (isPlaying && shouldContinue) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val deltaTime = currentTime - lastUpdateTime
                    lastUpdateTime = currentTime
                    
                    mediaPlayer?.let { player ->
                        if (player.isPlaying) {
                            currentPosition = player.currentPosition
                        } else {
                            // If player is not playing but we think it should be, update position manually
                            currentPosition = (currentPosition + (deltaTime * playbackSpeed)).toInt()
                            if (currentPosition >= duration) {
                                currentPosition = 0
                                isPlaying = false
                                shouldContinue = false
                            }
                        }
                    }
                    delay(UPDATE_INTERVAL)
                } catch (e: Exception) {
                    Log.e("AudioProcessor", "Error updating position", e)
                    shouldContinue = false
                }
            }
        }
    }
    
    fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                currentPosition = player.currentPosition
                isPlaying = false
                progressUpdateJob?.cancel()
            }
        }
    }
    
    fun resumePlayback() {
        mediaPlayer?.let { player ->
            if (!isPlaying) {
                player.start()
                isPlaying = true
                lastUpdateTime = System.currentTimeMillis()
                startPositionUpdates()
            }
        }
    }
    
    fun seekTo(position: Float) {
        mediaPlayer?.let { player ->
            val newPosition = (position * duration).toInt()
            player.seekTo(newPosition)
            currentPosition = newPosition
            lastUpdateTime = System.currentTimeMillis()
        }
    }
    
    fun getCurrentProgress(): Float {
        return if (duration > 0) {
            currentPosition.toFloat() / duration
        } else {
            0f
        }
    }
    
    fun release() {
        progressUpdateJob?.cancel()
        mediaPlayer?.let { player ->
            player.release()
            mediaPlayer = null
            isPlaying = false
            currentPosition = 0
            duration = 0
        }
    }
} 