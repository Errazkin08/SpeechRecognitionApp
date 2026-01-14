package com.example.speechrecognitionapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.util.Date

class Logger(private val context: Context) {

    val logFile: File = File(context.filesDir, "rtdb_cache.log")
    private var writer: FileWriter? = null

    fun start() {
        try {
            writer = FileWriter(logFile, true) // Append mode
            Log.i(TAG, "Logger started. Caching to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting logger", e)
        }
    }

    fun logPrediction(keyword: String, confidence: Float) {
        // Writes every prediction immediately to the cache file
        // Format: timestamp|keyword|confidence
        try {
            val timestamp = System.currentTimeMillis()
            val line = "$timestamp|$keyword|$confidence\n"
            writer?.append(line)
            writer?.flush()
            Log.d(TAG, "Logged to cache: $line")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log cache file", e)
        }
    }

    fun stop() {
        try {
            writer?.close()
            Log.i(TAG, "Logger stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logger", e)
        }
    }

    companion object {
        private val TAG = Logger::class.java.simpleName
    }
}
