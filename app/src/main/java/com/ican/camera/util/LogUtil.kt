package com.ican.camera.util

import android.util.Log

object LogUtil {
    private const val TAG = "ICanCamera"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun i(event: String) {
        Log.i(TAG, "[EVENT] $event at ${System.currentTimeMillis()}")
    }
}
