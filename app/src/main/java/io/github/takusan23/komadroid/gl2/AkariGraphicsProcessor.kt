package io.github.takusan23.komadroid.gl2

import android.opengl.GLES20
import android.view.Surface
import io.github.takusan23.komadroid.gl.InputSurface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

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

    suspend fun prepare() {
        withContext(openGlRelatedThreadDispatcher) {
            inputSurface.makeCurrent()
            textureRenderer.prepareShader()
            GLES20.glViewport(0, 0, width, height)
        }
    }

    suspend fun <T> genTextureId(action: (texId: Int) -> T): T = withContext(openGlRelatedThreadDispatcher) {
        textureRenderer.genTextureId(action)
    }

    suspend fun <T> genEffect(action: (width: Int, height: Int) -> T): T = withContext(openGlRelatedThreadDispatcher) {
        textureRenderer.genEffect(action)
    }

    suspend fun drawLoop(draw: suspend AkariGraphicsTextureRenderer.() -> Unit) {
        withContext(openGlRelatedThreadDispatcher) {
            while (isActive) {
                textureRenderer.prepareDraw()
                draw(textureRenderer)
                textureRenderer.drawEnd()
                inputSurface.swapBuffers()
            }
        }
    }

    suspend fun destroy() {
        withContext(openGlRelatedThreadDispatcher) {
            textureRenderer.destroy()
            inputSurface.destroy()
        }
        // もう使わない
        openGlRelatedThreadDispatcher.close()
    }
}