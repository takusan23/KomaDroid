package io.github.takusan23.komadroid.gl2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AkariVideoProcessorRenderer(
    width: Int,
    height: Int
) {
    private val mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    // Uniform 変数のハンドル
    private var sSurfaceTextureHandle = 0
    private var sCanvasTextureHandle = 0
    private var iDrawModeHandle = 0

    // テクスチャ ID
    private var surfaceTextureTextureId = 0
    private var canvasTextureTextureId = 0

    // Canvas 描画のため Bitmap
    private val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)

    init {
        mTriangleVertices.put(mTriangleVerticesData).position(0)
        //canvas.drawColor(Color.WHITE)
    }

    /**
     * バーテックスシェーダ、フラグメントシェーダーをコンパイルする。
     * GL スレッドから呼び出すこと。
     */
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
        sSurfaceTextureHandle = GLES20.glGetUniformLocation(mProgram, "sSurfaceTexture")
        checkGlError("glGetUniformLocation sSurfaceTexture")
        if (sSurfaceTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sSurfaceTexture")
        }
        sCanvasTextureHandle = GLES20.glGetUniformLocation(mProgram, "sCanvasTexture")
        checkGlError("glGetUniformLocation sCanvasTexture")
        if (sCanvasTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sCanvasTexture")
        }
        iDrawModeHandle = GLES20.glGetUniformLocation(mProgram, "iDrawMode")
        checkGlError("glGetUniformLocation iDrawMode")
        if (iDrawModeHandle == -1) {
            throw RuntimeException("Could not get attrib location for iDrawMode")

        }

        // テクスチャ ID を払い出してもらう
        // SurfaceTexture / Canvas Bitmap 用
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        surfaceTextureTextureId = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureTextureId)
        checkGlError("glBindTexture cameraTextureId")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        canvasTextureTextureId = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureTextureId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        // アルファブレンディング
        // Canvas で書いた際に、透明な部分は透明になるように
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError("glEnable GLES20.GL_BLEND")
    }

    /**
     * テクスチャ ID を払い出す。
     * [AkariSurfaceTexture]はコンストラクタでテクスチャ ID を必要とするが、描画時には[surfaceTextureTextureId]に切り替える。作成のためだけに必要。
     * 破棄する場合は使う側で呼び出してください。
     *
     * @param T 返り値
     * @param action 関数
     */
    fun <T> genTextureId(action: (texId: Int) -> T): T {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        return action(textures.first())
    }

    /**
     * 描画前に呼び出す。
     * GL スレッドから呼び出すこと。
     */
    fun prepareDraw() {
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Canvas に書く。
     * GL スレッドから呼び出すこと。
     */
    suspend fun drawCanvas(draw: suspend Canvas.() -> Unit) {
        // 書く
        draw(canvas)

        // 多分いる
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureTextureId)

        // テクスチャを転送
        // texImage2D、引数違いがいるので注意
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, canvasBitmap, 0)
        checkGlError("GLUtils.texImage2D")

        // 描画する
        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // テクスチャの ID をわたす
        GLES20.glUniform1i(sSurfaceTextureHandle, 0) // GLES20.GL_TEXTURE0
        GLES20.glUniform1i(sCanvasTextureHandle, 1) // GLES20.GL_TEXTURE1
        // モード切替
        GLES20.glUniform1i(iDrawModeHandle, FRAGMENT_SHADER_DRAW_MODE_CANVAS_BITMAP)
        checkGlError("glUniform1i sSurfaceTextureHandle sCanvasTextureHandle iDrawModeHandle")

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

        Matrix.setIdentityM(mSTMatrix, 0)
        Matrix.setIdentityM(mMVPMatrix, 0)

        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /**
     * SurfaceTexture を描画する。
     * GL スレッドから呼び出すこと。
     */
    suspend fun drawSurfaceTexture(
        akariSurfaceTexture: AkariSurfaceTexture,
        onTransform: ((mvpMatrix: FloatArray) -> Unit)? = null
    ) {
        // attachGlContext の前に呼ぶ必要あり。多分
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureTextureId)

        // 映像を OpenGL ES で使う準備
        akariSurfaceTexture.attachGl(surfaceTextureTextureId)
        akariSurfaceTexture.awaitUpdateTexImage()
        akariSurfaceTexture.getTransformMatrix(mSTMatrix)

        // 描画する
        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // テクスチャの ID をわたす
        GLES20.glUniform1i(sSurfaceTextureHandle, 0) // GLES20.GL_TEXTURE0
        GLES20.glUniform1i(sCanvasTextureHandle, 1) // GLES20.GL_TEXTURE1
        // モード切替
        GLES20.glUniform1i(iDrawModeHandle, FRAGMENT_SHADER_DRAW_MODE_SURFACE_TEXTURE)
        checkGlError("glUniform1i sSurfaceTextureHandle sCanvasTextureHandle iDrawModeHandle")

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

        // 行列を適用したい場合
        Matrix.setIdentityM(mMVPMatrix, 0)
        if (onTransform != null) {
            onTransform(mMVPMatrix)
        }

        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /** 破棄時に呼び出す */
    fun destroy() {
        //
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

        // iDrawMode に渡す定数
        private const val FRAGMENT_SHADER_DRAW_MODE_SURFACE_TEXTURE = 1
        private const val FRAGMENT_SHADER_DRAW_MODE_CANVAS_BITMAP = 2

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTextureCoord;
uniform sampler2D sCanvasTexture;
uniform samplerExternalOES sSurfaceTexture;

// 何を描画するか
// 1 SurfaceTexture（カメラや動画のデコード映像）
// 2 Bitmap（テキストや画像を描画した Canvas）
uniform int iDrawMode;

void main() { 
  // 出力色
  vec4 outColor = vec4(0., 0., 0., 1.);

  if (iDrawMode == 1) {
    outColor = texture2D(sSurfaceTexture, vTextureCoord);
  } else if (iDrawMode == 2) {
    // テクスチャ座標なので Y を反転
    outColor = texture2D(sCanvasTexture, vec2(vTextureCoord.x, 1.-vTextureCoord.y));
  }

  // 出力
  gl_FragColor = outColor;
}
"""
    }

}