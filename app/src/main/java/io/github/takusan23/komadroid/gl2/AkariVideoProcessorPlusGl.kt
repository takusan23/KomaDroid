package io.github.takusan23.komadroid.gl2

import android.view.Surface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

class AkariVideoProcessorPlusGl(private val outputSurface: Surface) {

    /** OpenGL 描画用スレッドの Kotlin Coroutine Dispatcher */
    @OptIn(DelicateCoroutinesApi::class)
    private val openGlRelatedThreadDispatcher = newSingleThreadContext("openGlRelatedThreadDispatcher")

    /**
     * 必要な OpenGL ES 周りのセットアップを行う
     */
    fun prepare() {
        // TODO EGL の用意
        // TODO テクスチャを描画するだけのフラグメントシェーダー、バーテックスシェーダを用意
    }

    fun addSource() {

    }

    fun removeSource() {

    }

    fun start() {

    }

    fun destroy() {

    }
}