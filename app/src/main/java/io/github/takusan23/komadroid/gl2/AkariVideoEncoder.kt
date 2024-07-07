package io.github.takusan23.komadroid.gl2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Surface を録画する */
class AkariVideoEncoder : MediaCodec.Callback() {

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private val handlerThread = HandlerThread("MediaCodecHandlerThread")
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private var currentJob: Job? = null
    private val mediaCodecCallbackChannel = Channel<MediaCodecAsyncState>()

    /** 書き込み先[Surface]。[prepare]後に呼び出せます */
    val inputSurface: Surface
        get() = mediaCodec!!.createInputSurface()

    /** エンコーダーを作る */
    fun prepare(
        outputFilePath: String,
        outputVideoWidth: Int,
        outputVideoHeight: Int,
        bitRate: Int = 1_000_000,
        frameRate: Int = 30,
        keyframeInterval: Int = 1,
        codecName: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        containerFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    ) {
        val videoMediaFormat = MediaFormat.createVideoFormat(codecName, outputVideoWidth, outputVideoHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        mediaCodec = MediaCodec.createEncoderByType(codecName).apply {
            setCallback(this@AkariVideoEncoder)
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        mediaMuxer = MediaMuxer(outputFilePath, containerFormat)
    }

    fun start() {
        currentJob = scope.launch {
            // MediaCodec 開始
            mediaCodec?.start()

            // MediaMuxer.addTrack の返り値
            var videoTrackIndex = -1

            // コールバックを待つ
            while (isActive) {
                when (val receiveAsyncState = mediaCodecCallbackChannel.receive()) {
                    is MediaCodecAsyncState.InputBuffer -> {
                        // Surface から入力するので何もしない
                    }

                    is MediaCodecAsyncState.OutputBuffer -> {
                        val outputIndex = receiveAsyncState.index
                        val bufferInfo = receiveAsyncState.info
                        // 書き込む
                        val encodedData = mediaCodec!!.getOutputBuffer(outputIndex)!!
                        mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        // 使い終わった
                        mediaCodec!!.releaseOutputBuffer(outputIndex, false)
                    }

                    is MediaCodecAsyncState.OutputFormat -> {
                        videoTrackIndex = mediaMuxer!!.addTrack(receiveAsyncState.format)
                        mediaMuxer!!.start() // addTrack のあと！
                    }
                }
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        mediaCodec?.stop()
    }

    fun destroy() {
        mediaCodec?.release()
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