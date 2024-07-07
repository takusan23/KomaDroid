package io.github.takusan23.komadroid.gl2

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/** [AkariVideoProcessorPlusGl]で動画を描画する */
class AkariVideoFrameTexture(initTexName: Int) : MediaCodec.Callback() {

    private var mediaCodec: MediaCodec? = null
    private var mediaExtractor: MediaExtractor? = null
    // private val mediaCodecAsyncCallbackStateFlow = MutableStateFlow<MediaCodecAsyncState?>(null)

    /** 映像をテクスチャとして利用できるやつ */
    val akariSurfaceTexture = AkariSurfaceTexture(initTexName)

    // MediaCodec の非同期コールバックが呼び出されるスレッド（Handler）
    private val handlerThread = HandlerThread("MediaCodecHandlerThread")

    private val mutex = Mutex()
    private val scope = MainScope()
    private var currentJob: Job? = null
    private val mediaCodecCallbackChannel = Channel<MediaCodecAsyncState>()

    fun prepareDecoder(filePath: String) {
        // 動画トラックを探す
        val mediaExtractor = MediaExtractor().apply {
            setDataSource(filePath)
        }
        this.mediaExtractor = mediaExtractor

        val videoTrackIndex = (0 until mediaExtractor.trackCount)
            .map { mediaExtractor.getTrackFormat(it) }
            .withIndex()
            .first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            .index

        mediaExtractor.selectTrack(videoTrackIndex)
        val mediaFormat = mediaExtractor.getTrackFormat(videoTrackIndex)
        val codecName = mediaFormat.getString(MediaFormat.KEY_MIME)!!

        handlerThread.start()
        mediaCodec = MediaCodec.createDecoderByType(codecName).apply {
            setCallback(this@AkariVideoFrameTexture, Handler(handlerThread.looper))
            configure(mediaFormat, akariSurfaceTexture.surface, null, 0)
            start()
        }
    }


    fun play() {
        currentJob = scope.launch {
            // 無限ループでコールバックを待つ
            while (isActive) {
                when (val receiveAsyncState = mediaCodecCallbackChannel.receive()) {
                    is MediaCodecAsyncState.InputBuffer -> {
                        val inputIndex = receiveAsyncState.index
                        val inputBuffer = mediaCodec?.getInputBuffer(inputIndex) ?: break
                        val size = mediaExtractor?.readSampleData(inputBuffer, 0) ?: break
                        if (size > 0) {
                            // デコーダーへ流す
                            mediaCodec?.queueInputBuffer(inputIndex, 0, size, mediaExtractor!!.sampleTime, 0)
                            mediaExtractor?.advance()
                        }
                    }

                    is MediaCodecAsyncState.OutputBuffer -> {
                        val outputIndex = receiveAsyncState.index
                        mediaCodec?.releaseOutputBuffer(outputIndex, true)
                        delay(33)
                    }
                }
            }
        }
    }

    fun pause() {
        currentJob?.cancel()
    }

    fun seekTo() {

    }

    fun destroy() {
        mediaExtractor?.release()
        mediaCodec?.release()
        akariSurfaceTexture.destroy()
        handlerThread
        scope.cancel()
    }

    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        runBlocking {
            mediaCodecCallbackChannel.send(MediaCodecAsyncState.InputBuffer(codec, index))
        }
    }

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        runBlocking {
            mediaCodecCallbackChannel.send(MediaCodecAsyncState.OutputBuffer(codec, index))
        }
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        // mediaCodecAsyncCallbackStateFlow.value = MediaCodecAsyncState.Error(codec, e)
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        // mediaCodecAsyncCallbackStateFlow.value = MediaCodecAsyncState.OutputFormatChanged(codec, format)
    }

    sealed interface MediaCodecAsyncState {
        data class InputBuffer(val codec: MediaCodec, val index: Int) : MediaCodecAsyncState
        data class OutputBuffer(val codec: MediaCodec, val index: Int) : MediaCodecAsyncState
    }

//    sealed interface MediaCodecAsyncState {
//        class InputBufferAvailable(val codec: MediaCodec, val index: Int) : MediaCodecAsyncState
//        class OutputBufferAvailable(val codec: MediaCodec, val index: Int, val info: MediaCodec.BufferInfo) : MediaCodecAsyncState
//        class Error(val codec: MediaCodec, val e: MediaCodec.CodecException) : MediaCodecAsyncState
//        class OutputFormatChanged(val codec: MediaCodec, val format: MediaFormat) : MediaCodecAsyncState
//    }
}