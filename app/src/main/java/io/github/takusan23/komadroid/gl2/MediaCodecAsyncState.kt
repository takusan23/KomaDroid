package io.github.takusan23.komadroid.gl2

import android.media.MediaCodec
import android.media.MediaFormat

sealed interface MediaCodecAsyncState {
    data class InputBuffer(val codec: MediaCodec, val index: Int) : MediaCodecAsyncState
    data class OutputBuffer(val codec: MediaCodec, val index: Int, val info: MediaCodec.BufferInfo) : MediaCodecAsyncState
    data class OutputFormat(val codec: MediaCodec, val format: MediaFormat) : MediaCodecAsyncState
}
