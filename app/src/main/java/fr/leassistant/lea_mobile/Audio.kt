package fr.leassistant.lea_mobile

import android.media.*
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import fr.leassistant.lea_mobile.R
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okio.ByteString
import kotlin.concurrent.thread

interface AudioHandler {
    fun onMicTalked(opusBuffer: ByteString)
}

class Audio(handler: AudioHandler) {
    private val handler = handler
    private val mic_rate = Constants.SampleRate._16000()
    private val mic_frame_size  = Constants.FrameSize._960()
    private val in_rate = Constants.SampleRate._48000()
    private val in_frame_size = Constants.FrameSize._1920()

    var codec = Opus()
    var writeAudioBuffer: MutableList<Short> = mutableListOf()
    var writeAudioBufferMutex = Mutex()

    var talk_enabled = false
    val talk_enabledMutex = Mutex()

    private fun record() {
        codec.encoderInit(mic_rate, Constants.Channels.mono(), Constants.Application.voip())
        codec.decoderInit(in_rate, Constants.Channels.mono())

        val audio_bufsize = AudioTrack.getMinBufferSize(
            in_rate.v,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioTrack(
            AudioManager.STREAM_MUSIC,
            in_rate.v, //sample rate
            AudioFormat.CHANNEL_OUT_MONO, //2 channel
            AudioFormat.ENCODING_PCM_16BIT, // 16-bit
            audio_bufsize,
            AudioTrack.MODE_STREAM);
        audio.play()

        val mic_bufsize = AudioRecord.getMinBufferSize(
            mic_rate.v,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val mic = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            mic_rate.v,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            mic_bufsize)
        mic.startRecording()

        thread(start = true) {
            GlobalScope.launch {
                while (true) {
                    writeAudioBufferMutex.lock()
                    val short_array = writeAudioBuffer.toShortArray()
                    writeAudioBufferMutex.unlock()

                    val w = audio.write(short_array, 0, short_array.size)

                    writeAudioBufferMutex.lock()
                    writeAudioBuffer =
                        writeAudioBuffer.slice(w until writeAudioBuffer.size).toMutableList()
                    writeAudioBufferMutex.unlock()
                }
            }
        }

        thread(start = true) {
            GlobalScope.launch {
                while (true) {
                    val buf = ShortArray(mic_frame_size.v)
                    val r = mic.read(buf, 0, buf.size)
                    talk_enabledMutex.lock()
                    if (talk_enabled) {
                        talk_enabledMutex.unlock()
                        val samples = buf.slice(0 until r)
                        val enc = codec.encode(samples.toShortArray(), mic_frame_size)
                        talk_enabledMutex.lock()
                        if (enc != null) {
                            val e = enc
                            if (talk_enabled)
                                handler.onMicTalked(ByteString.of(e, 0, e.size))
                        }
                    }
                    talk_enabledMutex.unlock()
                }
            }
        }
    }

    init {
        record()
    }

    fun startTalking() {
        GlobalScope.launch {
            talk_enabledMutex.lock()
            talk_enabled = true
            talk_enabledMutex.unlock()
        }
    }

    fun stopTalking() {
        GlobalScope.launch {
            talk_enabledMutex.lock()
            talk_enabled = false
            handler.onMicTalked(ByteString.of(ByteArray(0), 0, 0))
            talk_enabledMutex.unlock()
        }
    }

    fun decode(audioBytes: ByteString) : ShortArray? {
        return codec.decode(audioBytes.toByteArray(), Constants.FrameSize._1920())
    }

    fun play(samples: ShortArray) {
        GlobalScope.launch {
            writeAudioBufferMutex.lock()
            writeAudioBuffer.addAll(samples.toList())
            writeAudioBufferMutex.unlock()
        }
    }
}