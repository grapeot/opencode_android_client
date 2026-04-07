package com.yage.opencode_client.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class AudioRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        if (isRecording) {
            throw IllegalStateException("Recorder is already running")
        }

        val outputFile = File.createTempFile(
            AudioRecorderConfig.tempFilePrefix,
            AudioRecorderConfig.tempFileSuffix,
            context.cacheDir
        )
        val mediaRecorder = MediaRecorder()

        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(AudioRecorderConfig.outputSampleRate)
            mediaRecorder.setAudioChannels(AudioRecorderConfig.outputChannelCount)
            mediaRecorder.setAudioEncodingBitRate(AudioRecorderConfig.outputBitRate)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()

            recorder = mediaRecorder
            currentFile = outputFile
            Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start recording", error)
            mediaRecorder.release()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            currentFile = null
            throw error
        }
    }

    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val outputFile = currentFile

        return try {
            activeRecorder.stop()
            Log.d(TAG, "Recording stopped: ${outputFile?.absolutePath}")
            outputFile
        } catch (error: Exception) {
            Log.e(TAG, "Failed to stop recording", error)
            null
        } finally {
            activeRecorder.release()
            recorder = null
            currentFile = null
        }
    }

    suspend fun convertToPCM(m4aFile: File): ByteArray = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Converting M4A to PCM: ${m4aFile.absolutePath}")
            val decodeResult = decodeM4aToPCM(m4aFile)
            val pcmSamples = if (decodeResult.sampleRate != AudioRecorderConfig.targetPcmSampleRate) {
                resamplePCM(
                    decodeResult.samples,
                    decodeResult.sampleRate,
                    AudioRecorderConfig.targetPcmSampleRate
                )
            } else {
                decodeResult.samples
            }

            val pcmBytes = ByteBuffer
                .allocate(pcmSamples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            pcmSamples.forEach { sample ->
                pcmBytes.putShort(sample)
            }

            Log.d(
                TAG,
                "PCM conversion complete. inputRate=${decodeResult.sampleRate}, outputRate=${AudioRecorderConfig.targetPcmSampleRate}, bytes=${pcmBytes.array().size}"
            )
            pcmBytes.array()
        } catch (error: Exception) {
            Log.e(TAG, "speech.audio.decode failed for ${m4aFile.absolutePath}", error)
            throw SpeechTranscriptionException(
                kind = SpeechFailureKind.LOCAL_AUDIO,
                stage = "speech.audio.decode",
                detail = error.message ?: "Failed to decode recorded audio",
                cause = error
            )
        }
    }

    private fun decodeM4aToPCM(m4aFile: File): DecodedPCM {
        val extractor = MediaExtractor()
        val samples = ArrayList<Short>()

        try {
            extractor.setDataSource(m4aFile.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing audio mime type")

            val codec = MediaCodec.createDecoderByType(mimeType)
            var codecStarted = false
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                codecStarted = true

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                while (!outputDone) {
                    if (!inputDone) {
                        val inputBufferIndex = codec.dequeueInputBuffer(AudioRecorderConfig.codecTimeoutUs)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                                ?: throw IllegalStateException("Missing codec input buffer")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputBufferIndex = codec.dequeueOutputBuffer(info, AudioRecorderConfig.codecTimeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }

                        else -> {
                            if (outputBufferIndex >= 0) {
                                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                                    ?: throw IllegalStateException("Missing codec output buffer")
                                val chunk = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                outputBuffer.get(chunk)
                                outputBuffer.clear()

                                appendDecodedSamples(samples, chunk, channelCount)
                                codec.releaseOutputBuffer(outputBufferIndex, false)

                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }

                if (samples.isEmpty()) {
                    throw IllegalStateException("Decoded PCM is empty")
                }

                return DecodedPCM(
                    sampleRate = sampleRate,
                    samples = samples.toShortArray()
                )
            } finally {
                if (codecStarted) {
                    codec.stop()
                }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        throw IllegalStateException("No audio track found")
    }

    private fun appendDecodedSamples(samples: MutableList<Short>, chunk: ByteArray, channelCount: Int) {
        if (chunk.isEmpty()) {
            return
        }

        val shortBuffer = ByteBuffer
            .wrap(chunk)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val pcm = ShortArray(shortBuffer.remaining())
        shortBuffer.get(pcm)

        if (channelCount <= 1) {
            pcm.forEach { sample -> samples.add(sample) }
            return
        }

        var index = 0
        while (index < pcm.size) {
            samples.add(pcm[index])
            index += channelCount
        }
    }

    private fun resamplePCM(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): ShortArray = AudioResampler.resample(input, inputSampleRate, outputSampleRate)

    private data class DecodedPCM(
        val sampleRate: Int,
        val samples: ShortArray
    )

    private companion object {
        private const val TAG = "AudioRecorderManager"
    }
}

/**
 * Linear-interpolation PCM resampler.
 * Extracted as a top-level object so it can be unit-tested without Android Context.
 */
object AudioResampler {
    fun resample(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): ShortArray {
        if (input.isEmpty()) {
            return ShortArray(0)
        }
        if (inputSampleRate == outputSampleRate) {
            return input.copyOf()
        }

        val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
        val outputSize = max(1, (input.size * ratio).toInt())
        val output = ShortArray(outputSize)

        for (i in output.indices) {
            val sourcePosition = i / ratio
            val index = floor(sourcePosition).toInt()
            val nextIndex = min(index + 1, input.lastIndex)
            val fraction = sourcePosition - index

            val first = input[index].toDouble()
            val second = input[nextIndex].toDouble()
            val interpolated = first + ((second - first) * fraction)
            output[i] = interpolated
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return output
    }
}
