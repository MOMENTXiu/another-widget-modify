package com.tommasoberlose.anotherwidget.network.repository

import android.content.Context
import android.content.pm.PackageManager
import com.haroldadmin.cnradapter.NetworkResponseAdapterFactory
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.network.api.ApiServices
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.Locale

/**
 * Everything related to QWeather credentials and endpoints lives here, so the weather request and the
 * icon mapping never have to know how the account is authenticated.
 */
object QWeatherAuth {

    // Default API host. Current QWeather accounts get their own host (something like
    // "abcdefg.re.qweatherapi.com") that has to be pasted in the settings.
    const val DEFAULT_API_HOST = "devapi.qweather.com"

    fun apiKey(): String = Preferences.weatherProviderApiQWeather.trim()

    fun isConfigured(): Boolean = apiKey() != ""

    /**
     * Always returns a Retrofit-valid base URL: the user is allowed to paste the bare host, with or
     * without scheme or trailing slash.
     */
    fun baseUrl(): String = baseUrl(Preferences.weatherProviderQWeatherHost)

    fun baseUrl(host: String): String {
        val normalizedHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
        return "https://${normalizedHost.ifBlank { DEFAULT_API_HOST }}/"
    }
}

/**
 * QWeather can restrict a credential to an Android package name plus signing certificate. The
 * fingerprint is read from the installed APK rather than hard-coded, so debug and release builds
 * each send their own correct value.
 */
object QWeatherAndroidRestriction {

    fun headers(context: Context, enabled: Boolean = Preferences.weatherProviderQWeatherAndroidRestriction): Map<String, String> {
        if (!enabled) return emptyMap()
        val fingerprint = signingCertificateSha1(context) ?: return emptyMap()

        return mapOf(
            "X-Android-Package-Name" to context.packageName,
            "X-Android-Cert" to fingerprint
        )
    }

    private fun signingCertificateSha1(context: Context): String? = try {
        @Suppress("DEPRECATION")
        val signature = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures
            ?.firstOrNull()

        signature?.let {
            MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { byte ->
                String.format(Locale.US, "%02X", byte)
            }
        }
    } catch (ex: Exception) {
        // A fingerprint we cannot read is not worth failing the weather request over.
        null
    }
}

class QWeatherRepository(
    private val context: Context,
    baseUrl: String = QWeatherAuth.baseUrl(),
    private val apiKey: String = QWeatherAuth.apiKey(),
    private val androidRestriction: Boolean = Preferences.weatherProviderQWeatherAndroidRestriction
) {

    private val apiServiceQWeather: ApiServices.QWeatherService = getRetrofit(baseUrl).create(ApiServices.QWeatherService::class.java)

    suspend fun getWeather(latitude: Double, longitude: Double) = apiServiceQWeather.getHourlyForecast(
        coordinate(latitude),
        coordinate(longitude),
        apiKey,
        QWeatherAndroidRestriction.headers(context, androidRestriction)
    )

    // The API accepts at most two decimals, and the value goes in the URL path, so the locale must
    // not be allowed to introduce a decimal comma.
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.2f", value)

    companion object {
        private fun getRetrofit(baseUrl: String): Retrofit {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(NetworkResponseAdapterFactory())
                .build()
        }
    }
}
