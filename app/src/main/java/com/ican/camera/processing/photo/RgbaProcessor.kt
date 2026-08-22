package com.ican.camera.processing.photo

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class RgbaProcessor {

    private val vertexShaderCode = """#version 300 es
        in vec4 position;
        in vec2 inputTextureCoordinate;
        out vec2 textureCoordinate;
        void main() {
            gl_Position = position;
            textureCoordinate = inputTextureCoordinate;
        }
    """.trimIndent()

    private val fragmentShaderCode = """#version 300 es
        precision mediump float;
        in vec2 textureCoordinate;
        uniform sampler2D inputTexture;
        uniform float exposure;
        uniform float contrast;
        out vec4 fragColor;

        void main() {
            vec4 texColor = texture(inputTexture, textureCoordinate);
            vec3 color = texColor.rgb;
            
            // Minimal processing stage
            color = color * pow(2.0, exposure);
            color = (color - 0.5) * contrast + 0.5;
            
            fragColor = vec4(clamp(color, 0.0, 1.0), texColor.a);
        }
    """.trimIndent()

    private var program: Int = 0
    private var textureId: Int = 0
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var textureBuffer: FloatBuffer

    private val squareCoords = floatArrayOf(
        -1.0f,  1.0f,
        -1.0f, -1.0f,
         1.0f,  1.0f,
         1.0f, -1.0f
    )

    // Vertically flipped for glReadPixels
    private val textureCoords = floatArrayOf(
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 1.0f,
        1.0f, 0.0f
    )

    fun init() {
        program = GlUtil.createProgram(vertexShaderCode, fragmentShaderCode)
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(squareCoords)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords)
        textureBuffer.position(0)
    }

    fun process(
        bitmap: Bitmap,
        exposure: Float = 0.0f,
        contrast: Float = 1.0f
    ) {
        GLES30.glUseProgram(program)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "inputTexture"), 0)
        GlUtil.checkGlError("texImage2D RGBA")

        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "exposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "contrast"), contrast)

        val posHandle = GLES30.glGetAttribLocation(program, "position")
        GLES30.glEnableVertexAttribArray(posHandle)
        GLES30.glVertexAttribPointer(posHandle, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)

        val texHandle = GLES30.glGetAttribLocation(program, "inputTextureCoordinate")
        GLES30.glEnableVertexAttribArray(texHandle)
        GLES30.glVertexAttribPointer(texHandle, 2, GLES30.GL_FLOAT, false, 8, textureBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posHandle)
        GLES30.glDisableVertexAttribArray(texHandle)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}
