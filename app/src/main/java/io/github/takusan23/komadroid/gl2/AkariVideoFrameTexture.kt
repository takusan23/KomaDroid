package io.github.takusan23.komadroid.gl2

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** [AkariVideoProcessorPlusGl]で動画を描画する */
class AkariVideoFrameTexture(initTexName: Int) : MediaCodec.Callback() {

    private var mediaCodec: MediaCodec? = null
    private var mediaExtractor: MediaExtractor? = null

    /** 映像をテクスチャとして利用できるやつ */
    val akariSurfaceTexture = AkariSurfaceTexture(initTexName)

    // MediaCodec の非同期コールバックが呼び出されるスレッド（Handler）
    private val handlerThread = HandlerThread("MediaCodecHandlerThread")
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
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

                    is MediaCodecAsyncState.OutputFormat -> {
                        // デコーダーでは使われないはず
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
        handlerThread.quit()
        scope.cancel()
    }

    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        runBlocking {
            mediaCodecCallbackChannel.send(MediaCodecAsyncState.InputBuffer(codec, index))
        }
    }

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        runBlocking {
            mediaCodecCallbackChannel.send(MediaCodecAsyncState.OutputBuffer(codec, index, info))
        }
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        // do nothing
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        runBlocking {
            mediaCodecCallbackChannel.send(MediaCodecAsyncState.OutputFormat(codec, format))
        }
    }

}