package com.example.speechrecognitionapp

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.speechrecognitionapp.databinding.FragmentHomeBinding
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment(), RecordingCallback {

    private var audioRecordingService: AudioRecordingService? = null
    private var isServiceBound: Boolean = false
    private lateinit var binding: FragmentHomeBinding

    private var results = ArrayList<Result>()
    private lateinit var adapter: ResultAdapter

    private var sharedPreferences: SharedPreferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = activity?.let { PreferenceManager.getDefaultSharedPreferences(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater)
        val view = binding.root

        val listView = binding.listView
        adapter = ResultAdapter(results, activity?.applicationContext)
        listView.adapter = adapter

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        binding.btnRecord.setOnClickListener {
            if(isServiceBound) {
                // Stop the service, if running
                binding.btnRecord.text = "Record"
                stopService()
            } else {
                // Start the service, if not running
                binding.btnRecord.text = "Stop"
                startService()
            }
        }

    }
    override fun onDataUpdated(data: ArrayList<Result>) {
        Log.d(TAG, "Updated:" + data.size)
        activity?.runOnUiThread {
            adapter.clear()
            adapter.addAll(data)
            adapter.notifyDataSetChanged()
        }
    }

    override fun onStatusUpdate(status: String) {
        activity?.runOnUiThread {
            binding.statusTextView.text = status
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as AudioRecordingService.RunServiceBinder
            audioRecordingService = binder.service
            audioRecordingService?.setCallback(this@HomeFragment)
            isServiceBound = true
            audioRecordingService?.background()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioRecordingService = null
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isServiceBound) {
            activity?.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onStop() {
        super.onStop()
        // This logic was flawed and caused a race condition that destroyed the service.
        // The service should only be stopped via the button press.
        if (isServiceBound) {
            if (audioRecordingService?.isRecording == true) {
                Log.d(TAG, "App going to background, foregrounding service.")
                audioRecordingService?.foreground()
            }
        }
    }

    private fun startService() {
        val serviceIntent = Intent(activity, AudioRecordingService::class.java)

        try {
            val energyThreshold = sharedPreferences?.getString("energy", "0.005") // Lowered default
            //Log.d(TAG, "energyThreshold: $energyThreshold")
            val probabilityThreshold = sharedPreferences?.getString("probability", "0.002")
            //Log.d(TAG, "probabilityThreshold: $probabilityThreshold")
            val windowSize = sharedPreferences?.getString("window_size", "8000")
            //Log.d(TAG, "windowSize: $windowSize")
            val topK = sharedPreferences?.getString("top_k", "1")

            serviceIntent.putExtras(Bundle().apply {
                putDouble("energyThreshold", energyThreshold?.toDouble()!!)
                putFloat("probabilityThreshold", probabilityThreshold?.toFloat()!!)
                putInt("windowSize", windowSize?.toInt()!!)
                putInt("topK", topK?.toInt()!!)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        activity?.startService(serviceIntent)
        bindService()
        schedulePeriodicLogUpload()
    }

    private fun stopService() {
        unbindService()
        val serviceIntent = Intent(activity, AudioRecordingService::class.java)
        activity?.stopService(serviceIntent)
        WorkManager.getInstance(requireContext()).cancelUniqueWork("LogUploadWork")
        Log.i(TAG, "Periodic log upload worker canceled.")
    }

    private fun bindService() {
        val bindIntent = Intent(activity, AudioRecordingService::class.java)
        activity?.bindService(bindIntent, serviceConnection, AppCompatActivity.BIND_AUTO_CREATE)
    }

    private fun unbindService() {
        if (isServiceBound) {
            activity?.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // Use this for production
    private fun schedulePeriodicLogUpload() {
        val uploadWorkRequest = PeriodicWorkRequestBuilder<LogUploadWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
            "LogUploadWork",
            ExistingPeriodicWorkPolicy.KEEP,
            uploadWorkRequest
        )

        Log.i(TAG, "Periodic log upload worker scheduled.")
    }

    companion object {
        private val TAG = HomeFragment::class.java.simpleName
    }
}