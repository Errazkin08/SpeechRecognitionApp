package com.example.speechrecognitionapp

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        var editTextEnergy: EditTextPreference? = preferenceManager.findPreference("energy")
        var editTextProbability: EditTextPreference? = preferenceManager.findPreference("probability")
        var editTextWindowSize: EditTextPreference? = preferenceManager.findPreference("window_size")
        var editTextTopK: EditTextPreference? = preferenceManager.findPreference("top_k")

        editTextEnergy?.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        editTextProbability?.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        editTextWindowSize?.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        editTextTopK?.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
    }



}