package com.example.speechrecognitionapp

import com.google.firebase.database.IgnoreExtraProperties

/**
 * Represents a single log entry in the Realtime Database.
 * The timestamp is used as the key, so it is not included in the object itself.
 */
@IgnoreExtraProperties
data class LogEntry(
    val keyword: String? = null,
    val confidence: Double? = null
)
