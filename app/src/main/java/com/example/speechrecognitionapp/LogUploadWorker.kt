package com.example.speechrecognitionapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.io.File

class LogUploadWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "LogUploadWorker started.")
        val logFile = File(applicationContext.filesDir, "rtdb_cache.log")

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.w(TAG, "Log cache file is empty or does not exist. No work to do.")
            return Result.success()
        }

        val database = Firebase.database.reference
        // Use a Map to store timestamp as the key and the LogEntry as the value
        val entriesToUpload = mutableMapOf<String, LogEntry>()

        logFile.forEachLine {
            val parts = it.split("|")
            // The new format is timestamp|keyword|confidence, so we expect 3 parts
            if (parts.size == 3) {
                try {
                    val timestamp = parts[0]
                    val keyword = parts[1]
                    val confidence = parts[2].toDouble()
                    entriesToUpload[timestamp] = LogEntry(keyword, confidence)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not parse line: '$it'", e)
                }
            }
        }

        if (entriesToUpload.isEmpty()) {
            Log.w(TAG, "Log file had content, but no valid entries could be parsed.")
            // Clean up the invalid file to prevent future failed attempts
            logFile.delete()
            return Result.success()
        }

        if (entriesToUpload.size < 100) {
            Log.i(TAG, "Not enough logs to upload: ${entriesToUpload.size}. Accumulating more.")
            return Result.success()
        }

        Log.i(TAG, "Attempting to upload ${entriesToUpload.size} log entries to Realtime Database...")

        try {
            // Use updateChildren for a more efficient, single network call
            database.child("logs").updateChildren(entriesToUpload.toMap() as Map<String, Any>).await()
            Log.i(TAG, "Successfully uploaded ALL log entries in a single batch.")

            // If upload is successful, delete the local log file
            logFile.delete()
            Log.i(TAG, "Deleted local log file.")

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "A failure occurred during log upload to Realtime Database", e)
            return Result.retry()
        }
    }

    companion object {
        val TAG: String = LogUploadWorker::class.java.simpleName
    }
}