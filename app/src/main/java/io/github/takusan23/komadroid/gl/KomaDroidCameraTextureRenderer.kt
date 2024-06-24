package io.github.takusan23.komadroid.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 前面背面カメラを、OpenGL ES を使い、同時に重ねて描画する。
 * OpenGL 用スレッドで呼び出してください。
 */
class KomaDroidCameraTextureRenderer {

    private val mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    // Uniform 変数のハンドル
    private var sFrontCameraTextureHandle = 0
    private var sBackCameraTextureHandle = 0
    private var iDrawFrontCameraTextureHandle = 0

    // スレッドセーフに Bool 扱うため Mutex と CoroutineScope
    private val frameMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // カメラ映像が来ているか。カメラ映像が描画ループの速度よりも遅いので
    private var isAvailableFrontCameraFrame = false
    private var isAvailableBackCameraFrame = false

    // カメラ映像は SurfaceTexture を経由してフラグメントシェーダーでテクスチャとして使える
    private var frontCameraTextureId = -1
    private var backCameraTextureId = -1

    // SurfaceTexture。カメラ映像をテクスチャとして使えるやつ
    private var frontCameraSurfaceTexture: SurfaceTexture? = null
    private var backCameraSurfaceTexture: SurfaceTexture? = null

    // カメラ映像を流す Surface。SurfaceTexture として使われます
    var frontCameraInputSurface: Surface? = null
        private set
    var backCameraInputSurface: Surface? = null
        private set

    init {
        mTriangleVertices.put(mTriangleVerticesData).position(0)
    }

    /** バーテックスシェーダ、フラグメントシェーダーをコンパイルする。多分 GL スレッドから呼び出してください */
    fun createShader() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        sFrontCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "sFrontCameraTexture")
        checkGlError("glGetUniformLocation sFrontCameraTexture")
        if (sFrontCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sFrontCameraTexture")
        }
        sBackCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "sBackCameraTexture")
        checkGlError("glGetUniformLocation sBackCameraTexture")
        if (sBackCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sBackCameraTexture")
        }
        iDrawFrontCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "iDrawFrontCameraTexture")
        checkGlError("glGetUniformLocation iDrawFrontCameraTexture")
        if (iDrawFrontCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for iDrawFrontCameraTexture")
        }

        // テクスチャ ID を払い出してもらう
        // 前面カメラの映像、背面カメラの映像で2個分
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        // 1個目はフロントカメラ映像
        frontCameraTextureId = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frontCameraTextureId)
        checkGlError("glBindTexture cameraTextureId")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        // 2個目はバックカメラ映像
        backCameraTextureId = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backCameraTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        // glGenTextures で作ったテクスチャは SurfaceTexture で使う
        // カメラ映像は Surface 経由で受け取る
        frontCameraSurfaceTexture = SurfaceTexture(frontCameraTextureId)
        frontCameraInputSurface = Surface(frontCameraSurfaceTexture)
        backCameraSurfaceTexture = SurfaceTexture(backCameraTextureId)
        backCameraInputSurface = Surface(backCameraSurfaceTexture)

        // 新しいフレームが使える場合に呼ばれるイベントリスナー
        // 他のスレッドからも書き換わるので Mutex() する
        frontCameraSurfaceTexture?.setOnFrameAvailableListener {
            scope.launch {
                frameMutex.withLock {
                    isAvailableFrontCameraFrame = true
                }
            }
        }
        backCameraSurfaceTexture?.setOnFrameAvailableListener {
            scope.launch {
                frameMutex.withLock {
                    isAvailableBackCameraFrame = true
                }
            }
        }
    }

    /** SurfaceTexture のサイズを設定する */
    fun setSurfaceTextureSize(width: Int, height: Int) {
        frontCameraSurfaceTexture?.setDefaultBufferSize(width, height)
        backCameraSurfaceTexture?.setDefaultBufferSize(width, height)
    }

    /** 新しいフロントカメラの映像が来ているか */
    suspend fun isAvailableFrontCameraFrame() = frameMutex.withLock {
        if (isAvailableFrontCameraFrame) {
            isAvailableFrontCameraFrame = false
            true
        } else {
            false
        }
    }

    /** 新しいバックカメラの映像が来ているか */
    suspend fun isAvailableBackCameraFrame() = frameMutex.withLock {
        if (isAvailableBackCameraFrame) {
            isAvailableBackCameraFrame = false
            true
        } else {
            false
        }
    }

    /** フロントカメラ映像のテクスチャを更新する */
    fun updateFrontCameraTexture() {
        if (frontCameraSurfaceTexture?.isReleased == false) {
            frontCameraSurfaceTexture?.updateTexImage()
        }
    }

    /** バックカメラ映像のテクスチャを更新する */
    fun updateBackCameraTexture() {
        if (backCameraSurfaceTexture?.isReleased == false) {
            backCameraSurfaceTexture?.updateTexImage()
        }
    }

    /** 描画する。GL スレッドから呼び出してください */
    fun draw() {
        // Snapdragon だと glClear が無いと映像が乱れる
        // Google Pixel だと何も起きないのに、、、
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        // 描画する
        checkGlError("draw() start")
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // テクスチャの ID をわたす
        GLES20.glUniform1i(sFrontCameraTextureHandle, 0) // GLES20.GL_TEXTURE0 なので 0
        GLES20.glUniform1i(sBackCameraTextureHandle, 1) // GLES20.GL_TEXTURE1 なので 1
        checkGlError("glUniform1i sFrontCameraTextureHandle sBackCameraTextureHandle")

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        //  --- まずバックカメラ映像を描画する ---
        GLES20.glUniform1i(iDrawFrontCameraTextureHandle, 0)
        checkGlError("glUniform1i iDrawFrontCameraTextureHandle")

        // mMVPMatrix リセット
        Matrix.setIdentityM(mMVPMatrix, 0)
        // アスペクト比、カメラ入力が1:1で、プレビューが16:9で歪むので、よく分からないけど Matrix.scaleM する。謎
        Matrix.scaleM(mMVPMatrix, 0, 1.7f, 1f, 1f)

        backCameraSurfaceTexture?.getTransformMatrix(mSTMatrix)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        // --- 次にフロントカメラ映像を描画する ---
        GLES20.glUniform1i(iDrawFrontCameraTextureHandle, 1)
        checkGlError("glUniform1i iDrawFrontCameraTextureHandle")

        // mMVPMatrix リセット
        Matrix.setIdentityM(mMVPMatrix, 0)
        // 右上に移動させる
        // Matrix.translateM(mMVPMatrix, 0, 1f - 0.3f, 1f - 0.3f, 1f)
        // 右下に移動なら
        Matrix.translateM(mMVPMatrix, 0, 1f - 0.3f, -1f + 0.3f, 1f)
        // 半分ぐらいにする
        Matrix.scaleM(mMVPMatrix, 0, 0.3f, 0.3f, 1f)
        // アスペクト比、カメラ入力が1:1で、プレビューが16:9で歪むので、よく分からないけど Matrix.scaleM する。謎
        Matrix.scaleM(mMVPMatrix, 0, 1.7f, 1f, 1f)

        // 描画する
        frontCameraSurfaceTexture?.getTransformMatrix(mSTMatrix)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /** 破棄時に呼び出す */
    fun destroy() {
        scope.cancel()
        isAvailableBackCameraFrame = false
        isAvailableFrontCameraFrame = false
        frontCameraSurfaceTexture?.release()
        frontCameraInputSurface?.release()
        backCameraSurfaceTexture?.release()
        backCameraInputSurface?.release()
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    /**
     * GLSL（フラグメントシェーダー・バーテックスシェーダー）をコンパイルして、OpenGL ES とリンクする
     *
     * @throws GlslSyntaxErrorException 構文エラーの場合に投げる
     * @throws RuntimeException それ以外
     * @return 0 以外で成功
     */
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    /**
     * GLSL（フラグメントシェーダー・バーテックスシェーダー）のコンパイルをする
     *
     * @throws GlslSyntaxErrorException 構文エラーの場合に投げる
     * @throws RuntimeException それ以外
     * @return 0 以外で成功
     */
    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            shader = 0
        }
        return shader
    }

    companion object {
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        private val mTriangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;

uniform samplerExternalOES sFrontCameraTexture;
uniform samplerExternalOES sBackCameraTexture;

// sFrontCameraTexture を描画する場合は 1。
// sBackCameraTexture は 0。
uniform int iDrawFrontCameraTexture;

void main() { 
  // 出力色
  vec4 outColor = vec4(0., 0., 0., 1.);

  // どっちを描画するのか
  if (bool(iDrawFrontCameraTexture)) {
    // フロントカメラ（自撮り）
    vec4 cameraColor = texture2D(sFrontCameraTexture, vTextureCoord); 
    outColor = cameraColor;
  } else {
    // バックカメラ（外側）
    vec4 cameraColor = texture2D(sBackCameraTexture, vTextureCoord);
    outColor = cameraColor;
  }

  // 出力
  gl_FragColor = outColor;
}
"""
    }

}