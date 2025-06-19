package com.example.omnigraph

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
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
import kotlin.math.sqrt

/**
 * AudioProcessor handles the core logic for encoding audio into images and decoding images back into audio.
 * It specifically focuses on Method A (Channel Multiplexing) for robust and accurate transformations.
 */
class AudioProcessor(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying: Boolean = false
    private var currentPosition: Int = 0 // In milliseconds for MediaPlayer
    private var duration: Int = 0 // In milliseconds for MediaPlayer

    companion object {
        private const val SAMPLE_RATE = 44100 // Hz, standard for CD quality audio (e.g., WAV files)
        private const val BITS_PER_SAMPLE = 16 // Bits per audio sample (e.g., 16-bit PCM)
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8 // 2 bytes for 16-bit PCM
    }

    /**
     * Encodes raw 16-bit PCM audio data into a PNG image byte array using the specified method.
     * This operation is CPU-bound and is suspended to run on a background thread (Dispatchers.Default).
     *
     * @param audioData Raw 16-bit PCM audio byte array.
     * @param method The encoding method ("A"). Other methods are currently not supported.
     * @return Byte array of the encoded PNG image.
     * @throws IllegalArgumentException if input data is empty or method is invalid/unsupported.
     * @throws IllegalStateException if bitmap creation or compression fails.
     */
    suspend fun encodeAudioToImage(audioData: ByteArray, method: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            Log.d("AudioProcessor", "Starting audio encoding with method: $method")
            Log.d("AudioProcessor", "Input audio data size: ${audioData.size} bytes (16-bit PCM)")

            if (audioData.isEmpty()) {
                throw IllegalArgumentException("Input audio data is empty. Cannot encode an empty file.")
            }
            // Log a warning if audio data size isn't a multiple of bytes per sample, indicating potential malformed audio.
            if (audioData.size % BYTES_PER_SAMPLE != 0) {
                Log.w("AudioProcessor", "Audio data size (${audioData.size} bytes) is not a multiple of bytes per sample ($BYTES_PER_SAMPLE). This might indicate truncated audio data.")
            }

            // Convert raw 16-bit PCM bytes to a ShortArray (array of 16-bit samples).
            // Using ByteBuffer ensures correct endianness (WAV is typically Little-Endian).
            val audioShorts = ShortArray(audioData.size / BYTES_PER_SAMPLE)
            ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audioShorts)
            
            Log.d("AudioProcessor", "Converted audio to ${audioShorts.size} 16-bit samples.")

            // Convert 16-bit audio samples to 8-bit pixel intensity values (range 0-255).
            // This scales the full Short range (-32768 to 32767) linearly to 0-255.
            val audio8Bit = audioShorts.map { s ->
                ((s.toFloat() - Short.MIN_VALUE) / (Short.MAX_VALUE - Short.MIN_VALUE) * 255).toInt().toByte()
            }.toByteArray()
            Log.d("AudioProcessor", "Converted to 8-bit pixel data for image, size: ${audio8Bit.size} bytes.")

            val rgbPixelData: ByteArray // Will hold the flattened interleaved R,G,B pixel data for the image.
            val imageWidth: Int    // Calculated width of the resulting image.
            val imageHeight: Int   // Calculated height of the resulting image.

            when (method) {
                "A" -> {
                    Log.d("AudioProcessor", "Encoding using Method A (Channel Multiplexing).")
                    // Call encodeMethodA, which returns the pixel data and the calculated image dimensions.
                    val (encodedRgb, width, height) = encodeMethodA(audio8Bit)
                    rgbPixelData = encodedRgb
                    imageWidth = width
                    imageHeight = height
                }
                // Explicitly throw UnsupportedOperationException for other methods as they are not fully implemented.
                "B", "C" -> {
                    throw IllegalArgumentException("Encoding method '$method' is not supported in this version.")
                }
                else -> throw IllegalArgumentException("Invalid encoding method: $method. Supported methods are: A.")
            }
            
            Log.d("AudioProcessor", "Generated interleaved RGB pixel data of size ${rgbPixelData.size} bytes. Target image dimensions: ${imageWidth}x${imageHeight}.")

            // Validate calculated image dimensions before creating the Bitmap.
            if (imageWidth <= 0 || imageHeight <= 0) {
                throw IllegalStateException("Invalid bitmap dimensions calculated: ${imageWidth}x${imageHeight}. Cannot create image.")
            }

            // Create a new Bitmap with the calculated dimensions and ARGB_8888 configuration.
            val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
            Log.d("AudioProcessor", "Populating Bitmap pixels. Total pixels to set: ${imageWidth * imageHeight}.")
            for (y in 0 until imageHeight) {
                for (x in 0 until imageWidth) {
                    val index = (y * imageWidth + x) * 3 // Calculate the starting index for R, G, B components of the current pixel in rgbPixelData.
                    if (index + 2 < rgbPixelData.size) { // Ensure there are enough bytes for R, G, B for this pixel.
                        val r = rgbPixelData[index].toInt() and 0xFF // Convert signed byte back to unsigned int (0-255).
                        val g = rgbPixelData[index + 1].toInt() and 0xFF
                        val b = rgbPixelData[index + 2].toInt() and 0xFF
                        bitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                    } else {
                        // This case should ideally be rare if padding in encode methods is robust.
                        // It handles situations where rgbPixelData is smaller than the theoretically required pixels.
                        Log.w("AudioProcessor", "Pixel data out of bounds for pixel (${x},${y}). Padding with black.")
                        bitmap.setPixel(x, y, android.graphics.Color.BLACK) // Default to black if no data.
                    }
                }
            }

            // Compress the generated Bitmap into a PNG byte array.
            Log.d("AudioProcessor", "Compressing Bitmap to PNG byte array.")
            val outputStream = ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream) // 100 quality for PNG is lossless.
            bitmap.recycle() // Release the native bitmap memory immediately.

            if (!success) {
                throw IllegalStateException("Failed to compress Bitmap to PNG. Output stream might be invalid or out of memory.")
            }

            val result = outputStream.toByteArray()
            Log.d("AudioProcessor", "Successfully encoded audio to image. Output PNG size: ${result.size} bytes.")
            result
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error during audio encoding to image: ${e.message}", e)
            e.printStackTrace() // Print stack trace for detailed debugging.
            throw e // Re-throw the exception to allow the caller (e.g., ViewModel) to handle it.
        }
    }

    /**
     * Decodes an image byte array (PNG) back into 16-bit PCM audio data using the specified method.
     * This operation is CPU-bound and is suspended to run on a background thread (Dispatchers.Default).
     *
     * @param imageData Byte array of the PNG image.
     * @param method The decoding method ("A"). Other methods are currently not supported.
     * @return Byte array of the decoded 16-bit PCM audio.
     * @throws IllegalStateException if image decoding fails.
     * @throws IllegalArgumentException if method is invalid/unsupported.
     */
    suspend fun decodeImageToAudio(imageData: ByteArray, method: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            Log.d("AudioProcessor", "Starting image decoding with method: $method")
            Log.d("AudioProcessor", "Input image data size: ${imageData.size} bytes.")

            // Decode PNG byte array into an Android Bitmap.
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                ?: throw IllegalStateException("Failed to decode image data to Bitmap. Is the input a valid PNG image?")

            // CRITICAL FIX: Extract individual RGB channel data from the Bitmap.
            // For Method A, each color channel (Red, Green, Blue) stores a *sequential segment*
            // of the audio data across all its pixels, not interleaved per pixel.
            val width = bitmap.width
            val height = bitmap.height
            val totalPixels = width * height
            Log.d("AudioProcessor", "Bitmap dimensions: ${width}x${height}, total pixels: $totalPixels.")

            val redChannelData = ByteArray(totalPixels)    // Will store ALL Red pixel values (representing 1st audio segment).
            val greenChannelData = ByteArray(totalPixels)  // Will store ALL Green pixel values (representing 2nd audio segment).
            val blueChannelData = ByteArray(totalPixels)   // Will store ALL Blue pixel values (representing 3rd audio segment).
            
            // Iterate through each pixel of the Bitmap to extract its R, G, B components
            // and store them into their respective single-channel arrays.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val index = y * width + x // Calculate the 1D index for the current pixel in each channel array.
                    redChannelData[index] = android.graphics.Color.red(pixel).toByte()
                    greenChannelData[index] = android.graphics.Color.green(pixel).toByte()
                    blueChannelData[index] = android.graphics.Color.blue(pixel).toByte()
                }
            }
            bitmap.recycle() // Release the native bitmap memory immediately after extracting pixel data.

            val audio16BitShorts: ShortArray // Will hold the final 16-bit audio samples.

            when (method) {
                "A" -> {
                    Log.d("AudioProcessor", "Decoding using Method A (Channel Multiplexing).")
                    // Pass the three separate channel data arrays to decodeMethodA for reconstruction.
                    audio16BitShorts = decodeMethodA(redChannelData, greenChannelData, blueChannelData)
                }
                // Explicitly throw UnsupportedOperationException for other methods.
                "B", "C" -> {
                    throw IllegalArgumentException("Decoding method '$method' is not supported in this version.")
                }
                else -> throw IllegalArgumentException("Invalid decoding method: $method. Supported methods are: A.")
            }

            // Convert the 16-bit ShortArray (audio samples) back to a raw ByteArray for WAV output.
            // Using ByteBuffer ensures correct endianness (Little-Endian for WAV files).
            val audioByteBuffer = ByteBuffer.allocate(audio16BitShorts.size * BYTES_PER_SAMPLE)
            audioByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            audioByteBuffer.asShortBuffer().put(audio16BitShorts)
            val audioBytes = audioByteBuffer.array()

            // Write the WAV header and the raw audio data into a ByteArrayOutputStream.
            val outputStream = ByteArrayOutputStream()
            writeWavHeaderToStream(outputStream, audioBytes.size) // Write the WAV header.
            outputStream.write(audioBytes) // Write the raw 16-bit audio data.
            val result = outputStream.toByteArray()
            Log.d("AudioProcessor", "Successfully decoded image to audio. Output WAV size: ${result.size} bytes.")
            result
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error during image decoding to audio: ${e.message}", e)
            e.printStackTrace()
            throw e // Re-throw the exception for higher-level error handling.
        }
    }

    /* *********************************************************************************************
     * ENCODING METHOD IMPLEMENTATIONS
     *
     * These private methods implement the specific logic for each encoding scheme.
     ******************************************************************************************** */

    /**
     * Implements encoding Method A (Channel Multiplexing).
     * Stores audio sequentially in separate color channels (Red -> Green -> Blue).
     *
     * @param audio8Bit The 8-bit audio data (pixel intensity values).
     * @return A Triple containing:
     * 1. A ByteArray of interleaved R,G,B pixel data ready for Bitmap creation.
     * 2. The calculated width of the resulting square image.
     * 3. The calculated height of the resulting square image.
     */
    private fun encodeMethodA(audio8Bit: ByteArray): Triple<ByteArray, Int, Int> {
        val totalAudioLength = audio8Bit.size
        
        // Calculate sizes for each channel, splitting the total audio length as evenly as possible.
        // Any remainder from integer division goes to the blue channel.
        val redSize = totalAudioLength / 3
        val greenSize = totalAudioLength / 3
        val blueSize = totalAudioLength - redSize - greenSize

        // Create separate segments for Red, Green, and Blue channels.
        val redSegment = audio8Bit.copyOfRange(0, redSize)
        val greenSegment = audio8Bit.copyOfRange(redSize, redSize + greenSize)
        val blueSegment = audio8Bit.copyOfRange(redSize + greenSize, totalAudioLength)

        // Determine the image dimensions based on the longest audio segment.
        // This ensures all audio data (including padding) will fit into the square image.
        val maxSegmentLength = maxOf(redSegment.size, greenSegment.size, blueSegment.size)
        val side = ceil(sqrt(maxSegmentLength.toDouble())).toInt() // Calculate side of the square image.
        val requiredPixelsPerChannel = side * side // Total pixels needed for one channel's data (accounting for square padding).

        Log.d("AudioProcessor", "Method A Encode Logic: Red/Green/Blue segment lengths: ${redSegment.size}/${greenSegment.size}/${blueSegment.size}. Max segment length: $maxSegmentLength. Calculated image side: $side. Required pixels per channel: $requiredPixelsPerChannel.")

        // Create a single ByteArray to hold the final interleaved R, G, B pixel data for the image.
        // The total size is (required pixels per channel * 3 color channels).
        val rgbPixelData = ByteArray(requiredPixelsPerChannel * 3)

        // Populate the rgbPixelData array. Each pixel's R, G, B components are taken sequentially
        // from the corresponding Red, Green, and Blue audio segments. Padding with 0 if segment ends early.
        for (i in 0 until requiredPixelsPerChannel) {
            rgbPixelData[i * 3] = if (i < redSegment.size) redSegment[i] else 0.toByte() // Red channel gets data from redSegment.
            rgbPixelData[i * 3 + 1] = if (i < greenSegment.size) greenSegment[i] else 0.toByte() // Green channel gets data from greenSegment.
            rgbPixelData[i * 3 + 2] = if (i < blueSegment.size) blueSegment[i] else 0.toByte() // Blue channel gets data from blueSegment.
        }
        
        // Return the generated interleaved RGB pixel data along with the explicit width and height.
        return Triple(rgbPixelData, side, side)
    }

    // Placeholder for encoding Method B.
    private fun encodeMethodB(audio8Bit: ByteArray): Triple<ByteArray, Int, Int> {
        throw UnsupportedOperationException("Encoding method B is not supported in this version.")
    }

    // Placeholder for encoding Method C.
    private fun encodeMethodC(audioShorts: ShortArray): Triple<ByteArray, Int, Int> {
        throw UnsupportedOperationException("Encoding method C is not supported in this version.")
    }

    /* *********************************************************************************************
     * DECODING METHOD IMPLEMENTATIONS
     *
     * These private methods implement the specific logic for each decoding scheme.
     ******************************************************************************************** */

    /**
     * Implements decoding Method A (Channel Multiplexing).
     * Reconstructs audio by concatenating data extracted from the Red, Green, and Blue channels sequentially.
     *
     * @param redData The 8-bit data extracted from the Red channel of the image (first audio segment).
     * @param greenData The 8-bit data extracted from the Green channel of the image (second audio segment).
     * @param blueData The 8-bit data extracted from the Blue channel of the image (third audio segment).
     * @return A ShortArray representing the reconstructed 16-bit audio samples.
     */
    private fun decodeMethodA(redData: ByteArray, greenData: ByteArray, blueData: ByteArray): ShortArray {
        Log.d("AudioProcessor", "Method A Decode Logic: Red data size: ${redData.size}, Green data size: ${greenData.size}, Blue data size: ${blueData.size}.")

        // Concatenate the three separate 8-bit channel data arrays into a single combined 8-bit audio stream.
        // This directly reverses the sequential splitting and storage done during encoding.
        val totalAudio8BitSize = redData.size + greenData.size + blueData.size
        val audio8BitCombined = ByteArray(totalAudio8BitSize)

        // Efficiently copy each channel's data into the combined array at the correct starting offset.
        redData.copyInto(audio8BitCombined, 0)
        greenData.copyInto(audio8BitCombined, redData.size)
        blueData.copyInto(audio8BitCombined, redData.size + greenData.size)
        
        Log.d("AudioProcessor", "Method A Decode Logic: Combined 8-bit audio stream size: ${audio8BitCombined.size}.")

        // Convert the combined 8-bit pixel intensity values (range 0-255) back to 16-bit audio samples
        // (range Short.MIN_VALUE to Short.MAX_VALUE). This is the mathematically correct inverse scaling.
        val audio16BitShorts = audio8BitCombined.map { b ->
            val value = b.toInt() and 0xFF // Convert signed byte to unsigned integer (0-255).
            // Inverse scaling: ((value / 255.0) * (range of short)) + min_short_value.
            ((value.toFloat() / 255.0f * (Short.MAX_VALUE - Short.MIN_VALUE)) + Short.MIN_VALUE).toInt().toShort()
        }.toShortArray()

        Log.d("AudioProcessor", "Method A Decode Logic: Final 16-bit audio samples reconstructed: ${audio16BitShorts.size}.")
        return audio16BitShorts
    }

    // Placeholder for decoding Method B.
    private fun decodeMethodB(redData: ByteArray, greenData: ByteArray, blueData: ByteArray): ShortArray {
        throw UnsupportedOperationException("Decoding method B is not supported in this version.")
    }

    // Placeholder for decoding Method C.
    private fun decodeMethodC(redData: ByteArray, greenData: ByteArray, blueData: ByteArray): ShortArray {
        throw UnsupportedOperationException("Decoding method C is not supported in this version.")
    }

    /* *********************************************************************************************
     * WAV HEADER UTILITIES
     *
     * These private methods assist in creating standard WAV file headers (RIFF, fmt, data chunks).
     ******************************************************************************************** */

    /**
     * Writes a standard WAV header to the provided ByteArrayOutputStream.
     * The header is configured for 16-bit PCM, Mono, 44.1 kHz audio.
     *
     * @param output The ByteArrayOutputStream to write the header to.
     * @param dataSize The size of the raw audio data (in bytes) that will follow this header.
     */
    private fun writeWavHeaderToStream(output: ByteArrayOutputStream, dataSize: Int) {
        val totalAudioLen = dataSize
        val longSampleRate = SAMPLE_RATE.toLong()
        val byteRate = (BYTES_PER_SAMPLE * SAMPLE_RATE).toLong()
        val channels = 1 // Mono audio.
        val bitsPerSample = BITS_PER_SAMPLE // 16-bit audio.

        // Use ByteBuffer with Little-Endian order for writing header values, as WAV files are typically Little-Endian.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk (12 bytes)
        header.put("RIFF".toByteArray())          // Chunk ID: "RIFF" (4 bytes).
        header.putInt((totalAudioLen + 36))       // Chunk Size: Total file size minus 8 bytes (for "RIFF" ID and this "Chunk Size" field itself).
        header.put("WAVE".toByteArray())          // Format: "WAVE" (4 bytes).

        // FMT sub-chunk (24 bytes)
        header.put("fmt ".toByteArray())          // Subchunk1 ID: "fmt " (4 bytes).
        header.putInt(16)                         // Subchunk1 Size: 16 (for PCM format).
        header.putShort(1)                        // Audio Format: 1 (for PCM - Pulse Code Modulation).
        header.putShort(channels.toShort())       // Num Channels: 1 (Mono).
        header.putInt(longSampleRate.toInt())     // Sample Rate (e.g., 44100 Hz).
        header.putInt(byteRate.toInt())           // Byte Rate: SampleRate * NumChannels * BitsPerSample/8.
        header.putShort((channels * BYTES_PER_SAMPLE).toShort()) // Block Align: NumChannels * BitsPerSample/8 (bytes per sample frame).
        header.putShort(bitsPerSample.toShort())  // Bits per Sample (e.g., 16 bits).

        // DATA sub-chunk (8 bytes + dataSize)
        header.put("data".toByteArray())          // Subchunk2 ID: "data" (4 bytes).
        header.putInt(totalAudioLen)              // Subchunk2 Size: Actual size of the audio data (in bytes).

        output.write(header.array()) // Write the complete 44-byte WAV header to the stream.
    }

    /**
     * Writes a standard WAV header to the provided FileOutputStream.
     * The header is configured for 16-bit PCM, Mono, 44.1 kHz audio.
     *
     * @param output The FileOutputStream to write the header to.
     * @param dataSize The size of the raw audio data (in bytes) that will follow this header.
     */
    private fun writeWavHeaderToFile(output: FileOutputStream, dataSize: Int) {
        val totalAudioLen = dataSize
        val longSampleRate = SAMPLE_RATE.toLong()
        val byteRate = (BYTES_PER_SAMPLE * SAMPLE_RATE).toLong()
        val channels = 1
        val bitsPerSample = BITS_PER_SAMPLE

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray())
        header.putInt((totalAudioLen + 36))
        header.put("WAVE".toByteArray())

        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(longSampleRate.toInt())
        header.putInt(byteRate.toInt())
        header.putShort((channels * BYTES_PER_SAMPLE).toShort())
        header.putShort(bitsPerSample.toShort())

        header.put("data".toByteArray())
        header.putInt(totalAudioLen)

        output.write(header.array())
    }

    /* *********************************************************************************************
     * AUDIO PLAYBACK FUNCTIONALITY
     *
     * These methods integrate with Android's MediaPlayer for playing decoded audio.
     ******************************************************************************************** */

    /**
     * Plays the provided raw 16-bit PCM audio data using Android's MediaPlayer.
     * A temporary WAV file is created in the app's cache directory for MediaPlayer to consume.
     *
     * @param audioData The raw 16-bit PCM audio data as a ByteArray.
     */
    fun playAudio(audioData: ByteArray) {
        try {
            Log.d("AudioProcessor", "Attempting to play audio with data size: ${audioData.size} bytes.")
            
            // Release any existing MediaPlayer instance to prevent resource leaks before starting new playback.
            mediaPlayer?.release()
            mediaPlayer = null
            
            // Create a temporary WAV file in the app's cache directory. MediaPlayer requires a file path.
            val tempFile = File(context.cacheDir, "temp_audio_playback.wav")
            Log.d("AudioProcessor", "Creating temporary WAV file at: ${tempFile.absolutePath}.")
            
            // Write the WAV header and the raw audio data to the temporary file.
            FileOutputStream(tempFile).use { output ->
                writeWavHeaderToFile(output, audioData.size) // Write the WAV header.
                output.write(audioData)                       // Write the raw audio data.
            }
            
            Log.d("AudioProcessor", "Temporary WAV file created successfully. Path: ${tempFile.absolutePath}.")
            
            // Initialize and configure MediaPlayer.
            mediaPlayer = MediaPlayer().apply {
                // Set audio attributes for proper audio routing and focus management.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.path) // Set the data source to the temporary WAV file.
                
                // Set a listener for when playback completes.
                setOnCompletionListener { _ ->
                    Log.d("AudioProcessor", "Playback completed.")
                    this@AudioProcessor.isPlaying = false // Update internal state.
                    this@AudioProcessor.currentPosition = 0 // Reset position.
                    tempFile.delete() // Clean up the temporary file after playback finishes.
                }
                // Set a listener for playback errors.
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioProcessor", "MediaPlayer error occurred: what=$what, extra=$extra.")
                    this@AudioProcessor.isPlaying = false // Update internal state.
                    this@AudioProcessor.currentPosition = 0 // Reset position.
                    tempFile.delete() // Clean up on error as well.
                    true // Return true to indicate the error was handled.
                }
                prepare() // Prepare the MediaPlayer synchronously. For UI-blocking operations, consider prepareAsync().
            }
            
            Log.d("AudioProcessor", "MediaPlayer prepared successfully.")
            
            duration = mediaPlayer?.duration ?: 0 // Get the total duration of the audio in milliseconds.
            Log.d("AudioProcessor", "Audio duration: $duration ms.")
            
            mediaPlayer?.start() // Start playback.
            isPlaying = true // Update internal state.
            currentPosition = 0 // Reset playback position to the beginning for new playback.

            // Start a separate thread to continuously update the current playback position.
            // This is used by the ViewModel for UI progress updates.
            startPositionUpdates()
            
            Log.d("AudioProcessor", "Playback started.")
            
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Error during audio playback: ${e.message}", e)
            e.printStackTrace()
            // Reset playback state on error to ensure a clean state.
            isPlaying = false
            currentPosition = 0
            duration = 0
        }
    }
    
    /**
     * Starts a background thread to continuously update the current playback position.
     * This position can be used by the ViewModel to update UI elements like progress bars.
     */
    private fun startPositionUpdates() {
        // This thread runs in the background and does not directly interact with the UI.
        // UI updates (if any) should be handled by the ViewModel collecting this progress.
        Thread {
            while (isPlaying && mediaPlayer != null) {
                try {
                    mediaPlayer?.let { player ->
                        currentPosition = player.currentPosition // Update current position from MediaPlayer.
                        // The ViewModel collects this progress via getCurrentProgress()
                    }
                    Thread.sleep(100) // Update interval (e.g., every 100ms for smoother progress display).
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt() // Restore interrupt status if interrupted.
                    Log.d("AudioProcessor", "Position update thread interrupted.")
                    break // Exit the loop.
                } catch (e: Exception) {
                    Log.e("AudioProcessor", "Error updating playback position: ${e.message}", e)
                    break // Exit the loop on other errors.
                }
            }
        }.start()
    }
    
    /**
     * Pauses the current audio playback.
     */
    fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                currentPosition = player.currentPosition // Save current position before pausing.
                isPlaying = false
                Log.d("AudioProcessor", "Playback paused at ${currentPosition}ms.")
            }
        }
    }
    
    /**
     * Resumes the paused audio playback.
     */
    fun resumePlayback() {
        mediaPlayer?.let { player ->
            if (!isPlaying) {
                player.start()
                isPlaying = true
                Log.d("AudioProcessor", "Playback resumed from ${currentPosition}ms.")
                startPositionUpdates() // Ensure position updates restart if they were paused.
            }
        }
    }
    
    /**
     * Seeks the audio playback to a specific progress percentage.
     *
     * @param progress A float value between 0.0 (beginning) and 1.0 (end).
     */
    fun seekTo(progress: Float) {
        mediaPlayer?.let { player ->
            if (duration > 0) {
                val newPositionMs = (progress * duration).toInt()
                player.seekTo(newPositionMs)
                currentPosition = newPositionMs // Update internal current position.
                Log.d("AudioProcessor", "Seeked to: ${newPositionMs}ms (progress: $progress).")
            }
        }
    }
    
    /**
     * Gets the current playback progress as a float between 0.0 and 1.0.
     * This is typically called by the ViewModel to update the UI.
     */
    fun getCurrentProgress(): Float {
        return if (duration > 0) {
            // Ensure progress is clamped between 0 and 1.
            currentPosition.toFloat() / duration.toFloat().coerceAtLeast(1f) // Avoid division by zero if duration is 0.
        } else {
            0f
        }
    }
    
    /**
     * Releases all MediaPlayer resources. This method must be called when the AudioProcessor
     * instance is no longer needed (e.g., in ViewModel's onCleared()) to prevent memory leaks
     * and free up underlying audio resources.
     */
    fun release() {
        Log.d("AudioProcessor", "Releasing MediaPlayer resources.")
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop() // Stop playback if active.
            }
            player.release() // Release native MediaPlayer resources.
        }
        mediaPlayer = null // Nullify reference to prevent invalid access.
        isPlaying = false
        currentPosition = 0
        duration = 0
    }
}