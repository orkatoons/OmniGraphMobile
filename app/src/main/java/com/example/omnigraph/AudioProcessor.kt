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

class AudioProcessor(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying: Boolean = false
    private var currentPosition: Int = 0
    private var duration: Int = 0
    private var progressUpdateJob: kotlinx.coroutines.Job? = null
    
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    suspend fun encodeAudioToImage(audioData: ByteArray): ByteArray? {
        try {
            // Convert audio data to 16-bit samples
            val samples = ByteBuffer.wrap(audioData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .let { buffer ->
                    ShortArray(buffer.remaining()) { buffer.get() }
                }

            // Convert to 8-bit for image encoding (normalize to 0-255)
            val audioBytes = ByteArray(samples.size)
            for (i in samples.indices) {
                // Normalize from -32768..32767 to 0..255
                audioBytes[i] = ((samples[i].toInt() + 32768) * 255 / 65535).toByte()
            }

            // Split audio data into three equal parts for RGB channels
            val totalSamples = audioBytes.size
            val samplesPerChannel = totalSamples / 3
            val redChannel = audioBytes.copyOfRange(0, samplesPerChannel)
            val greenChannel = audioBytes.copyOfRange(samplesPerChannel, samplesPerChannel * 2)
            val blueChannel = audioBytes.copyOfRange(samplesPerChannel * 2, totalSamples)

            // Calculate image dimensions (square)
            val side = Math.ceil(Math.sqrt(samplesPerChannel.toDouble())).toInt()
            val totalPixels = side * side

            // Create bitmap
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(totalPixels)

            // Add header in first pixel (total samples)
            pixels[0] = (totalSamples and 0xFF) or ((totalSamples shr 8) shl 8)

            // Fill pixels with channel data
            for (i in 0 until totalPixels) {
                if (i > 0) { // Skip header
                    val r = if (i < redChannel.size) redChannel[i].toInt() and 0xFF else 0
                    val g = if (i < greenChannel.size) greenChannel[i].toInt() and 0xFF else 0
                    val b = if (i < blueChannel.size) blueChannel[i].toInt() and 0xFF else 0
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            bitmap.setPixels(pixels, 0, side, 0, 0, side, side)

            // Convert to PNG
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            bitmap.recycle()

            return outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error encoding audio to image", e)
            return null
        }
    }

    suspend fun decodeImageToAudio(imageData: ByteArray): ByteArray? {
        try {
            // Decode image
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            if (bitmap == null) return null

            val width = bitmap.width
            val height = bitmap.height
            Log.d("AudioProcessor", "Decoding image: ${width}x${height} pixels")
            
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            Log.d("AudioProcessor", "Total pixels: ${pixels.size}")

            // Read header (total samples)
            val totalSamples = (pixels[0] and 0xFF) or ((pixels[0] shr 8) and 0xFF shl 8)
            val samplesPerChannel = totalSamples / 3
            Log.d("AudioProcessor", "Total samples from header: $totalSamples")
            Log.d("AudioProcessor", "Samples per channel: $samplesPerChannel")

            // Extract channel data
            val redChannel = ByteArray(samplesPerChannel)
            val greenChannel = ByteArray(samplesPerChannel)
            val blueChannel = ByteArray(samplesPerChannel)

            // Extract RGB channels
            var redIndex = 0
            var greenIndex = 0
            var blueIndex = 0

            // Process each pixel after the header
            for (i in 1 until pixels.size) {
                val pixel = pixels[i]
                if (redIndex < samplesPerChannel) {
                    redChannel[redIndex++] = ((pixel shr 16) and 0xFF).toByte()
                }
                if (greenIndex < samplesPerChannel) {
                    greenChannel[greenIndex++] = ((pixel shr 8) and 0xFF).toByte()
                }
                if (blueIndex < samplesPerChannel) {
                    blueChannel[blueIndex++] = (pixel and 0xFF).toByte()
                }
            }

            Log.d("AudioProcessor", "Channel extraction complete:")
            Log.d("AudioProcessor", "Red channel samples: $redIndex")
            Log.d("AudioProcessor", "Green channel samples: $greenIndex")
            Log.d("AudioProcessor", "Blue channel samples: $blueIndex")

            // Combine channels back into single array
            val audioBytes = ByteArray(totalSamples)
            System.arraycopy(redChannel, 0, audioBytes, 0, samplesPerChannel)
            System.arraycopy(greenChannel, 0, audioBytes, samplesPerChannel, samplesPerChannel)
            System.arraycopy(blueChannel, 0, audioBytes, samplesPerChannel * 2, samplesPerChannel)

            // Convert back to 16-bit audio
            val samples = ShortArray(totalSamples)
            for (i in audioBytes.indices) {
                // Denormalize from 0-255 back to -32768..32767
                // Using the exact reverse of the encoding process
                val normalized = audioBytes[i].toInt() and 0xFF
                samples[i] = ((normalized * 65535 / 255) - 32768).toShort()
            }

            Log.d("AudioProcessor", "Converted to 16-bit samples: ${samples.size}")

            bitmap.recycle()

            // Create WAV file with proper header
            val outputStream = ByteArrayOutputStream()
            writeWavHeaderToStream(outputStream, samples.size * 2) // 2 bytes per sample
            
            // Write audio data
            ByteBuffer.allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    samples.forEach { putShort(it) }
                }
                .array()
                .also { outputStream.write(it) }

            val finalAudioData = outputStream.toByteArray()
            Log.d("AudioProcessor", "Final audio data size: ${finalAudioData.size} bytes")
            Log.d("AudioProcessor", "Expected duration: ${finalAudioData.size / (SAMPLE_RATE * 2)} seconds")

            return finalAudioData
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error decoding image to audio", e)
            e.printStackTrace()
            return null
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
        val total = rgbData.size / 3
        val audioData = ByteArray(total)
        
        for (i in 0 until total) {
            audioData[i] = rgbData[i * 3] // Use red channel
        }
        
        return audioData
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
            
            val splitPoints = listOf(total / 3, 2 * total / 3)
            Log.d("AudioProcessor", "Method A: Split points at ${splitPoints[0]} and ${splitPoints[1]}")
            
            val red = audio8Bit.copyOfRange(0, splitPoints[0])
            val green = audio8Bit.copyOfRange(splitPoints[0], splitPoints[1])
            val blue = audio8Bit.copyOfRange(splitPoints[1], total)
            
            val side = ceil(sqrt(red.size.toDouble())).toInt()
            val required = side * side
            
            Log.d("AudioProcessor", "Method A: Creating ${side}x${side} image")
            
            val paddedRed = red.copyOf(required)
            val paddedGreen = green.copyOf(required)
            val paddedBlue = blue.copyOf(required)
            
            return ByteArray(side * side * 3).apply {
                for (i in 0 until side * side) {
                    this[i * 3] = paddedRed[i]
                    this[i * 3 + 1] = paddedGreen[i]
                    this[i * 3 + 2] = paddedBlue[i]
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
            // For now, just use method A as a fallback
            val audio8Bit = audioShorts.map { ((it.toFloat() + 32768) / 65535 * 255).toInt().toByte() }.toByteArray()
            return encodeMethodA(audio8Bit)
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
                setOnPreparedListener { player ->
                    Log.d("AudioProcessor", "MediaPlayer prepared successfully")
                    this@AudioProcessor.duration = player.duration
                    Log.d("AudioProcessor", "Audio duration: $duration ms")
                    player.start()
                    this@AudioProcessor.isPlaying = true
                    this@AudioProcessor.currentPosition = 0
                    startPositionUpdates()
                }
                setOnCompletionListener { _ ->
                    Log.d("AudioProcessor", "Playback completed")
                    this@AudioProcessor.isPlaying = false
                    this@AudioProcessor.currentPosition = 0
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioProcessor", "MediaPlayer error: what=$what, extra=$extra")
                    this@AudioProcessor.isPlaying = false
                    this@AudioProcessor.currentPosition = 0
                    true
                }
                prepareAsync()
            }
            
            Log.d("AudioProcessor", "MediaPlayer initialization complete")
            tempFile.delete()
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error playing audio", e)
            Log.e("AudioProcessor", "Error details: ${e.message}")
            e.printStackTrace()
            isPlaying = false
            currentPosition = 0
            duration = 0
        }
    }
    
    private fun startPositionUpdates() {
        Thread {
            while (isPlaying) {
                try {
                    mediaPlayer?.let { player ->
                        currentPosition = player.currentPosition
                    }
                    Thread.sleep(100) // Update every 100ms
                } catch (e: Exception) {
                    Log.e("AudioProcessor", "Error updating position", e)
                    break
                }
            }
        }.start()
    }
    
    fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                currentPosition = player.currentPosition
                isPlaying = false
            }
        }
    }
    
    fun resumePlayback() {
        mediaPlayer?.let { player ->
            if (!isPlaying) {
                player.start()
                isPlaying = true
            }
        }
    }
    
    fun seekTo(position: Float) {
        mediaPlayer?.let { player ->
            val newPosition = (position * duration).toInt()
            player.seekTo(newPosition)
            currentPosition = newPosition
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
        mediaPlayer?.let { player ->
            player.release()
            mediaPlayer = null
            isPlaying = false
            currentPosition = 0
            duration = 0
        }
    }
} 