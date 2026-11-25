package com.example.mobileobs

import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LayerTransformFilter : BaseFilterRender() {
    private val mvpMatrix = FloatArray(16)
    private var mvpMatrixHandle = 0
    
    // 圖層屬性：位置、縮放、旋轉、裁切 (0.0 ~ 0.5)
    var posX = 0f
    var posY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var rotationAngle = 0f
    var cropLeft = 0f
    var cropRight = 0f
    var cropTop = 0f
    var cropBottom = 0f

    private lateinit var vertexBuffer: FloatBuffer

    // 支援裁切的 UV 座標更新
    fun updateGeometry() {
        val uMin = cropLeft
        val uMax = 1f - cropRight
        val vMin = cropBottom
        val vMax = 1f - cropTop

        val vertices = floatArrayOf(
            // X, Y, Z,    U, V
            -1f, -1f, 0f,  uMin, vMin,
             1f, -1f, 0f,  uMax, vMin,
            -1f,  1f, 0f,  uMin, vMax,
             1f,  1f, 0f,  uMax, vMax
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        vertexBuffer.position(0)

        // 計算移動、旋轉、縮放矩陣
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.translateM(mvpMatrix, 0, posX, posY, 0f)
        Matrix.rotateM(mvpMatrix, 0, rotationAngle, 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }

    override fun initGlFilter(context: android.content.Context) {
        updateGeometry()
        // 載入基礎頂點與片段著色器，綁定 uMVPMatrix
        // (使用 RootEncoder 內建 Shader 管線渲染)
    }

    override fun drawFilter() {
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {}
}
