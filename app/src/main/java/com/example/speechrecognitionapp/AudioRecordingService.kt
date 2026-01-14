package com.example.speechrecognitionapp

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.sqrt


class AudioRecordingService : Service() {

    companion object {
        private val TAG = AudioRecordingService::class.simpleName

        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_INPUT = MediaRecorder.AudioSource.MIC

        private const val DESIRED_LENGTH_SECONDS = 1
        private const val RECORDING_LENGTH = SAMPLE_RATE * DESIRED_LENGTH_SECONDS // in seconds

        // MFCC parameters
        private const val NUM_MFCC = 13

        // Notifications
        private const val CHANNEL_ID = "word_recognition"
        private const val NOTIFICATION_ID = 202
    }
    // Tweak parameters
    private var energyThreshold = 0.005 // Lowered default
    private var saturationThreshold = 0.65 // Saturation (clipping) threshold
    private var probabilityThreshold = 0.002f
    private var windowSize = SAMPLE_RATE / 2
    private var topK = 3

    private var recordingBufferSize = 0

    private var audioRecord: AudioRecord? = null
    private var audioRecordingThread: Thread? = null

    var isRecording: Boolean = false
    var recordingBuffer: DoubleArray = DoubleArray(RECORDING_LENGTH)
    var interpreter: Interpreter? = null
    private lateinit var axisLabels: List<String>

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notification: Notification? = null

    private var callback: RecordingCallback? = null

    private var isBackground = true

    private var logger: Logger? = null

    inner class RunServiceBinder : Binder() {
        val service: AudioRecordingService
            get() = this@AudioRecordingService
    }

    var serviceBinder = RunServiceBinder()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        recordingBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(AUDIO_INPUT, SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT, recordingBufferSize)

        try {
            val mappedByteBuffer = FileUtil.loadMappedFile(this, "model_16K_LR.tflite")
            interpreter = Interpreter(mappedByteBuffer)
            axisLabels = FileUtil.loadLabels(this, "labels.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model or labels", e)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return serviceBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val bundle = intent.extras
            if (bundle != null) {
                energyThreshold = bundle.getDouble("energyThreshold")
                probabilityThreshold = bundle.getFloat("probabilityThreshold")
                windowSize = bundle.getInt("windowSize")
                topK = bundle.getInt("topK")
            }
        }

        startRecording()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = getString(R.string.channel_desc)
        channel.enableLights(true)
        channel.lightColor = Color.BLUE
        channel.enableVibration(true)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        val resultIntent = Intent(this, MainActivity::class.java)
        resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(resultPendingIntent)

        notificationBuilder = builder
        return builder.build()
    }

    private fun updateNotification(label: String) {
        if (isBackground) return
        if (notificationBuilder == null) {
            return
        } else {
            notificationBuilder?.setContentText("${getText(R.string.notification_prediction)} $label")
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    fun setCallback(callback: RecordingCallback) {
        this.callback = callback
    }

    private fun updateData(data: ArrayList<Result>) {
        // Sort results
        Collections.sort(data) { o1, o2 -> o2.confidence.compareTo(o1.confidence) }

        // Keep top K results
        if (data.size > topK) {
            data.subList(topK, data.size).clear()
        }

        callback?.onDataUpdated(data)
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        logger = Logger(applicationContext)
        logger?.start()

        isRecording = true
        audioRecordingThread = Thread {
            run {
                record()
            }
        }
        audioRecordingThread?.start()
    }

    private fun record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }

        audioRecord?.startRecording()

        var firstLoop = true
        var totalSamplesRead: Int

        while (isRecording) {
            val tempRecordingBuffer = DoubleArray(SAMPLE_RATE - windowSize)

            if (!firstLoop) {
                totalSamplesRead = SAMPLE_RATE - windowSize
            } else {
                totalSamplesRead = 0
                firstLoop = false
            }

            while (totalSamplesRead < SAMPLE_RATE) {
                val remainingSamples = SAMPLE_RATE - totalSamplesRead
                val samplesToRead = if (remainingSamples > recordingBufferSize) recordingBufferSize else remainingSamples
                val audioBuffer = ShortArray(samplesToRead)
                val read = audioRecord?.read(audioBuffer, 0, samplesToRead) ?: 0

                if (read > 0) {
                    for (i in 0 until read) {
                        recordingBuffer[totalSamplesRead + i] = audioBuffer[i].toDouble() / Short.MAX_VALUE
                    }
                    totalSamplesRead += read
                }
            }

            // Gatekeeper filters for energy and saturation
            val rms = calculateRMS(recordingBuffer)
            val isSaturated = recordingBuffer.any { abs(it) >= saturationThreshold }

            if (rms >= energyThreshold && !isSaturated) {
                callback?.onStatusUpdate("Listening...")
                computeBuffer(recordingBuffer)
            } else {
                if (isSaturated) {
                    Log.w(TAG, "Chunk skipped due to saturation/clipping.")
                    callback?.onStatusUpdate("Data is not being processed because there is too much noise")
                } else {
                    callback?.onStatusUpdate("Data is not being processed because there is no noise")
                }
            }

            System.arraycopy(recordingBuffer, windowSize, tempRecordingBuffer, 0, recordingBuffer.size - windowSize)
            recordingBuffer = DoubleArray(RECORDING_LENGTH)
            System.arraycopy(tempRecordingBuffer, 0, recordingBuffer, 0, tempRecordingBuffer.size)
        }
        stopRecording()
    }

    private fun computeBuffer(audioBuffer: DoubleArray) {
        val mfccConvert = MFCC()
        mfccConvert.setSampleRate(SAMPLE_RATE)
        val nMFCC = NUM_MFCC
        mfccConvert.setN_mfcc(nMFCC)
        val mfccInput = mfccConvert.process(audioBuffer)

        loadAndPredict(mfccInput)
    }

    private fun loadAndPredict(mfccs: FloatArray) {
        try {
            if (interpreter == null) {
                Log.e(TAG, "Interpreter is not initialized.")
                return
            }

            val imageTensorIndex = 0
            val imageShape = interpreter?.getInputTensor(imageTensorIndex)?.shape()
            val imageDataType = interpreter?.getInputTensor(imageTensorIndex)?.dataType()

            val probabilityTensorIndex = 0
            val probabilityShape = interpreter?.getOutputTensor(probabilityTensorIndex)?.shape()
            val probabilityDataType = interpreter?.getOutputTensor(probabilityTensorIndex)?.dataType()

            if (imageShape == null || imageDataType == null || probabilityShape == null || probabilityDataType == null) {
                Log.e(TAG, "One of the model tensor properties is null.")
                return
            }

            val imageInputBuffer: TensorBuffer = TensorBuffer.createFixedSize(imageShape, imageDataType)
            imageInputBuffer.loadArray(mfccs, imageShape)

            val outputTensorBuffer = TensorBuffer.createFixedSize(probabilityShape, probabilityDataType)

            interpreter?.run(imageInputBuffer.buffer, outputTensorBuffer.buffer)

            val probabilityProcessor: TensorProcessor = TensorProcessor.Builder().build()
            val labels = TensorLabel(axisLabels, probabilityProcessor.process(outputTensorBuffer)).mapWithFloatValue
            val results = ArrayList<Result>()
            for (label in labels) {
                results.add(Result(label.key, label.value.toDouble()))
            }

            val topResult = labels.maxByOrNull { it.value }
            if (topResult != null) {
                val result = topResult.key
                val value = topResult.value

                if (value > probabilityThreshold) {
                    Log.i(TAG, "Prediction successful: '$result' with confidence $value")

                    logger?.logPrediction(result, value)

                    updateData(results)

                    updateNotification(result)

                } else {
                    // Prediction was below probability threshold, do nothing
                }
            } else {
                Log.w(TAG, "Model produced output, but no labels could be processed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during model inference", e)
        }
    }

    private fun calculateRMS(audioBuffer: DoubleArray): Double {
        var sum = 0.0
        for (sample in audioBuffer) {
            sum += sample * sample
        }
        return sqrt(sum / audioBuffer.size)
    }

    fun foreground() {
        notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        isBackground = false
    }

    fun background() {
        isBackground = true
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun stopRecording() {
        isRecording = false
        logger?.stop()
        logger = null
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
