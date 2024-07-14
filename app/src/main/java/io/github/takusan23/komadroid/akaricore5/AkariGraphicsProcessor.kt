package io.github.takusan23.komadroid.akaricore5

import android.opengl.GLES20
import android.view.Surface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * OpenGL ES の上に構築された、映像フレームを作るやつ
 *
 * @param outputSurface 描画先。SurfaceView や MediaRecorder
 * @param width 映像の幅。フレームバッファーオブジェクトのために必要です
 * @param height 映像の高さ。フレームバッファーオブジェクトのために必要です
 */
class AkariGraphicsProcessor(
    outputSurface: Surface,
    private val width: Int,
    private val height: Int
) {
    /** OpenGL 描画用スレッドの Kotlin Coroutine Dispatcher */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val openGlRelatedThreadDispatcher = newSingleThreadContext("openGlRelatedThreadDispatcher")

    private val inputSurface = InputSurface(outputSurface)
    private val textureRenderer = AkariGraphicsTextureRenderer(width, height)

    /** [AkariGraphicsTextureRenderer]等の用意をします */
    suspend fun prepare() {
        withContext(openGlRelatedThreadDispatcher) {
            inputSurface.makeCurrent()
            textureRenderer.prepareShader()
            GLES20.glViewport(0, 0, width, height)
        }
    }

    /** [AkariGraphicsSurfaceTexture]を作る際の引数 */
    suspend fun <T> genTextureId(action: (texId: Int) -> T): T = withContext(openGlRelatedThreadDispatcher) {
        textureRenderer.genTextureId(action)
    }

    suspend fun <T> genEffect(action: (width: Int, height: Int) -> T): T = withContext(openGlRelatedThreadDispatcher) {
        textureRenderer.genEffect(action)
    }

    /**
     * コルーチンがキャンセルされるまで描画を続ける
     *
     * @param draw このブロックは GL スレッドから呼び出されます
     */
    suspend fun drawLoop(draw: suspend AkariGraphicsTextureRenderer.() -> Unit) {
        withContext(openGlRelatedThreadDispatcher) {
            while (isActive) {
                textureRenderer.prepareDraw()
                draw(textureRenderer)
                ensureActive() // draw とかで時間がかかった場合、キャンセル済みかもしれないので  TODO 本当に必要かみる
                textureRenderer.drawEnd()
                inputSurface.swapBuffers()
            }
        }
    }

    /**
     * 一回だけ描画する
     *
     * @param draw このブロックは GL スレッドから呼び出されます
     */
    suspend fun drawOneshot(draw: suspend AkariGraphicsTextureRenderer.() -> Unit) {
        withContext(openGlRelatedThreadDispatcher) {
            textureRenderer.prepareDraw()
            draw(textureRenderer)
            textureRenderer.drawEnd()
            inputSurface.swapBuffers()
        }
    }

    /**
     * 破棄する
     * コルーチンキャンセル時に呼び出す場合、[kotlinx.coroutines.NonCancellable]をつけて呼び出す必要があります。
     *
     * @param preClean [AkariGraphicsTextureRenderer.destroy]よりも前に呼ばれる
     */
    suspend fun destroy(preClean: (suspend () -> Unit)? = null) {
        // 破棄自体も GL 用スレッドで呼び出す必要が多分ある
        withContext(openGlRelatedThreadDispatcher) {
            if (preClean != null) {
                preClean()
            }
            textureRenderer.destroy()
            inputSurface.destroy()
        }
        // もう使わない
        openGlRelatedThreadDispatcher.close()
    }
}