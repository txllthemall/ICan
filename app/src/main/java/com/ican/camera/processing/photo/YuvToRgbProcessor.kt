package com.ican.camera.processing.photo

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class YuvToRgbProcessor {

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
        uniform sampler2D yTexture;
        uniform sampler2D uTexture;
        uniform sampler2D vTexture;
        uniform float exposure;
        uniform float contrast;
        out vec4 fragColor;

        void main() {
            float y = texture(yTexture, textureCoordinate).r;
            float u = texture(uTexture, textureCoordinate).r - 0.5;
            float v = texture(vTexture, textureCoordinate).r - 0.5;

            // BT.601 limited range to RGB
            // y = (y_raw - 16/255) * (255/219)
            // But cameras often provide full range YUV. 
            // We'll use full range for now to avoid washed out blacks if source is full.
            float r = y + 1.402 * v;
            float g = y - 0.3441 * u - 0.7141 * v;
            float b = y + 1.772 * u;

            vec3 color = vec3(r, g, b);
            
            // Minimal processing stage
            color = color * pow(2.0, exposure);
            color = (color - 0.5) * contrast + 0.5;
            
            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private var program: Int = 0
    private val textures = IntArray(3)
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
        GLES30.glGenTextures(3, textures, 0)
        for (i in 0..2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(squareCoords)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords)
        textureBuffer.position(0)
    }

    fun process(
        yPlane: ByteBuffer, yStride: Int,
        uPlane: ByteBuffer, uStride: Int,
        vPlane: ByteBuffer, vStride: Int,
        width: Int, height: Int,
        exposure: Float = 0.0f,
        contrast: Float = 1.0f
    ) {
        GLES30.glUseProgram(program)
        
        yPlane.rewind()
        uPlane.rewind()
        vPlane.rewind()

        // Upload Y
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, yStride)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, width, height, 0, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, yPlane)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "yTexture"), 0)

        // Upload U
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, uStride)
        // Note: U/V planes are typically half width/height.
        // Also note: many YUV_420_888 implementations have pixelStride > 1 (interleaved).
        // This simple luminence approach only works well if pixelStride == 1.
        // For Phase 2C fix, we'll keep it simple but aware.
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, width / 2, height / 2, 0, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, uPlane)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 1)

        // Upload V
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[2])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, vStride)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, width / 2, height / 2, 0, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, vPlane)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "vTexture"), 2)

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GlUtil.checkGlError("glTexImage2D YUV")

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
        GLES30.glDeleteTextures(3, textures, 0)
    }
}
