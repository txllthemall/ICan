package com.ican.camera.processing.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import com.ican.camera.processing.ICanAutoConfig
import com.ican.camera.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class ProcessedPhotoStrategy(
    private val context: Context,
    private val config: ICanAutoConfig = ICanAutoConfig()
) : PhotoProcessingStrategy {

    override suspend fun processPhoto(imageProxy: ImageProxy): ProcessingResult = withContext(Dispatchers.Default) {
        val tStart = SystemClock.elapsedRealtimeNanos()
        LogUtil.i("PROCESS_INPUT_READY format=${imageProxy.format} res=${imageProxy.width}x${imageProxy.height}")

        try {
            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = imageProxy.imageInfo.rotationDegrees

            // 1. Process to Bitmap
            val outputBitmap = if (imageProxy.format == ImageFormat.JPEG) {
                processJpeg(imageProxy, width, height, rotation)
            } else {
                processYuv(imageProxy, width, height, rotation)
            }
            
            // 2. Save
            LogUtil.i("ENCODE_START")
            val outputUri = saveToMediaStore(outputBitmap)
            LogUtil.i("MEDIASTORE_SAVED Total Latency: ${(SystemClock.elapsedRealtimeNanos() - tStart) / 1_000_000}ms")
            
            ProcessingResult(success = true, outputUri = outputUri)

        } catch (e: Exception) {
            LogUtil.e("Processing failed", e)
            ProcessingResult(success = false, error = e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processJpeg(imageProxy: ImageProxy, width: Int, height: Int, rotation: Int): Bitmap {
        LogUtil.i("JPEG_DECODE_START")
        val bitmap = imageProxy.toBitmap()
        LogUtil.i("JPEG_DECODE_COMPLETE")
        
        return applyGpuProcessing(bitmap, width, height, rotation)
    }

    private fun processYuv(imageProxy: ImageProxy, width: Int, height: Int, rotation: Int): Bitmap {
        val eglCore = EglCore()
        eglCore.init()
        
        val processor = YuvToRgbProcessor()
        processor.init()

        // Create FBO as render target
        val fbo = IntArray(1)
        val renderTex = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, renderTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, renderTex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, renderTex[0], 0)
        
        GLES30.glViewport(0, 0, width, height)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        LogUtil.i("GPU_PROCESS_START (YUV)")
        processor.process(
            yPlane.buffer, yPlane.rowStride,
            uPlane.buffer, uPlane.rowStride,
            vPlane.buffer, vPlane.rowStride,
            width, height,
            exposure = 0.0f,
            contrast = 1.0f
        )
        
        val rgbaBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbaBuffer)
        
        processor.release()
        GLES30.glDeleteTextures(1, renderTex, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        eglCore.release()
        LogUtil.i("GPU_PROCESS_COMPLETE")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rgbaBuffer.rewind()
        bitmap.copyPixelsFromBuffer(rgbaBuffer)
        
        return rotateBitmap(bitmap, rotation)
    }

    private fun applyGpuProcessing(bitmap: Bitmap, width: Int, height: Int, rotation: Int): Bitmap {
        val eglCore = EglCore()
        eglCore.init()
        
        val processor = RgbaProcessor()
        processor.init()

        val fbo = IntArray(1)
        val renderTex = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, renderTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, renderTex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, renderTex[0], 0)
        
        GLES30.glViewport(0, 0, width, height)

        LogUtil.i("GPU_PROCESS_START (RGBA)")
        processor.process(bitmap, exposure = 0.0f, contrast = 1.0f)
        
        val rgbaBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbaBuffer)
        
        processor.release()
        GLES30.glDeleteTextures(1, renderTex, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        eglCore.release()
        LogUtil.i("GPU_PROCESS_COMPLETE")

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rgbaBuffer.rewind()
        resultBitmap.copyPixelsFromBuffer(rgbaBuffer)
        
        return rotateBitmap(resultBitmap, rotation)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveToMediaStore(bitmap: Bitmap): Uri? {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ICan-Camera")
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        
        resolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }
        
        return uri
    }
}
