package io.github.takusan23.komadroid.gl2

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * SurfaceTexture を[AkariVideoProcessorPlusGl]で使うため使いやすくしただけのクラス。
 * コンストラクタはどのスレッドからでも作れるはず。
 *
 * このクラスのインスタンスを作るためには、[initTexId]が引数で、そのテクスチャ ID の作成には glGenTextures が必要で、そのためには GL コンテキストを準備する必要がある。
 * 幸いにも、GL コンテキストとテクスチャ ID は生成後に変更できるため、
 * コンストラクタに渡すテクスチャ ID はどの GL コンテキストでも大丈夫だと思う。
 *
 * 実際に描画する際は、[attachToGLContext]でテクスチャ ID を指定するので、本当にコンストラクタの方は関係ないテクスチャ ID でいいはず。
 *
 * @param initTexId SurfaceTexture 作成時に TexId を作らないといけないので、多分どの GL Context でも良い（アタッチで変えられる）
 */
class AkariSurfaceTexture(private val initTexId: Int) {

    private val surfaceTexture = SurfaceTexture(initTexId)
    private val _isAvailableFrameFlow = MutableStateFlow(false)

    /** SurfaceTexture から新しいフレームが来ているかの Flow */
    val isAvailableFrameFlow = _isAvailableFrameFlow.asStateFlow()

    /** [SurfaceTexture]へ映像を渡す[Surface] */
    val surface = Surface(surfaceTexture)

    init {
        surfaceTexture.setOnFrameAvailableListener {
            // StateFlow はスレッドセーフが約束されているので
            _isAvailableFrameFlow.value = true
        }
    }

    /**
     * [SurfaceTexture.setDefaultBufferSize] を呼び出す
     * Camera2 API の解像度、SurfaceTexture の場合はここで決定する
     */
    fun setTextureSize(width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width, height)
    }

    /**
     * GL コンテキストを切り替え、テクスチャ ID の変更を行う。
     * GL スレッドから呼び出すこと。
     *
     * @param texId テクスチャ ID
     */
    fun attachGl(texId: Int) {
        surfaceTexture.detachFromGLContext()
        surfaceTexture.attachToGLContext(texId)
    }

    /** 新しいフレームが来るまで待って、[SurfaceTexture.updateTexImage]を呼び出す */
    suspend fun awaitUpdateTexImage() {
        // フラグが来たら折る
        _isAvailableFrameFlow.first { it /* == true */ }
        _isAvailableFrameFlow.value = false
        surfaceTexture.updateTexImage()
    }

    /** テクスチャが更新されていれば、[SurfaceTexture.updateTexImage]を呼び出す */
    fun checkAndUpdateTexImage() {
        val isAvailable = _isAvailableFrameFlow.value
        if (isAvailable) {
            _isAvailableFrameFlow.value = false
            surfaceTexture.updateTexImage()
        }
    }

    /** [SurfaceTexture.setDefaultBufferSize]を呼ぶ */
    fun setDefaultBufferSize(width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width, height)
    }

    /** [SurfaceTexture.getTransformMatrix]を呼ぶ */
    fun getTransformMatrix(mtx: FloatArray) {
        surfaceTexture.getTransformMatrix(mtx)
    }

    /**
     * 破棄する
     * GL スレッドから呼び出すこと（テクスチャを破棄したい）
     */
    fun destroy() {
        val textures = intArrayOf(initTexId)
        GLES20.glDeleteTextures(1, textures, 0)
        surface.release()
        surfaceTexture.release()
    }
}