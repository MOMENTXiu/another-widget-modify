package com.tommasoberlose.anotherwidget.components

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.databinding.QweatherSettingsLayoutBinding
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.network.WeatherNetworkApi
import com.tommasoberlose.anotherwidget.network.repository.QWeatherAuth
import com.tommasoberlose.anotherwidget.utils.openURI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * QWeather credentials. They are kept in the app preferences only: nothing is committed to the
 * repository, and the signing fingerprint used for the Android application restriction is read from
 * the installed APK at request time instead of being stored.
 *
 * "Test" checks the values currently typed in, without saving them and without touching the widget.
 */
class BottomSheetQWeatherSettings(context: Context, callback: () -> Unit) : BottomSheetDialog(context, R.style.BottomSheetDialogTheme) {

    private var binding: QweatherSettingsLayoutBinding = QweatherSettingsLayoutBinding.inflate(android.view.LayoutInflater.from(context))

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        binding.apiKey.editText?.setText(Preferences.weatherProviderApiQWeather)
        binding.apiHost.editText?.setText(Preferences.weatherProviderQWeatherHost)
        binding.androidRestrictionSwitch.isChecked = Preferences.weatherProviderQWeatherAndroidRestriction

        binding.actionOpenProvider.setOnClickListener {
            context.openURI(CONSOLE_URL)
        }

        binding.actionTest.setOnClickListener {
            DebugLogger.d("QWeatherSettings", "connection test clicked")
            testCredentials()
        }

        binding.actionSaveKey.setOnClickListener {
            DebugLogger.d("QWeatherSettings",
                "credentials saved host=${binding.apiHost.editText?.text.toString().trim()} " +
                    "androidRestriction=${binding.androidRestrictionSwitch.isChecked} keyProvided=${binding.apiKey.editText?.text.toString().trim().isNotEmpty()}")
            Preferences.weatherProviderApiQWeather = binding.apiKey.editText?.text.toString().trim()
            Preferences.weatherProviderQWeatherHost = binding.apiHost.editText?.text.toString().trim()
            Preferences.weatherProviderQWeatherAndroidRestriction = binding.androidRestrictionSwitch.isChecked

            callback.invoke()
            dismiss()
        }

        setContentView(binding.root)
    }

    private fun testCredentials() {
        binding.checkResult.isVisible = false
        setTesting(true)

        scope.launch {
            val check = WeatherNetworkApi(context).verifyCredentials(
                binding.apiHost.editText?.text.toString(),
                binding.apiKey.editText?.text.toString().trim(),
                binding.androidRestrictionSwitch.isChecked
            )

            setTesting(false)
            binding.checkResult.text = check.message
            binding.checkResult.setTextColor(ContextCompat.getColor(context, if (check.success) R.color.colorAccent else R.color.errorColorText))
            binding.checkResult.isVisible = true
            DebugLogger.d("QWeatherSettings", "connection test result success=${check.success}")
        }
    }

    private fun setTesting(testing: Boolean) {
        binding.actionTest.isEnabled = !testing
        binding.actionTest.text = context.getString(if (testing) R.string.action_testing else R.string.action_test)
    }

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }

    companion object {
        private const val CONSOLE_URL = "https://console.qweather.com/"
    }
}
