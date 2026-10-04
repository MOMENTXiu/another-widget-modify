package com.tommasoberlose.anotherwidget.helpers

import android.Manifest
import android.content.Context
import android.util.Log
import com.chibatching.kotpref.Kotpref
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.network.WeatherNetworkApi
import com.tommasoberlose.anotherwidget.services.LocationService
import com.tommasoberlose.anotherwidget.ui.fragments.MainFragment
import com.tommasoberlose.anotherwidget.ui.widgets.MainWidget
import com.tommasoberlose.anotherwidget.utils.checkGrantedPermission
import com.tommasoberlose.anotherwidget.utils.isDarkTheme
import org.greenrobot.eventbus.EventBus


/**
 * Created by tommaso on 08/10/17.
 */

object WeatherHelper {

    // Rendered as the generic "unknown weather" icon: used when a provider returns a condition we
    // have no icon for, instead of failing the whole update.
    const val UNKNOWN_ICON = "unknown"

    suspend fun updateWeather(context: Context, flowId: String = DebugLog.newFlowId(), trigger: String = "unknown") {
        Kotpref.init(context)
        val networkApi = WeatherNetworkApi(context)
        DebugLogger.d("WeatherHelper",
            "updateWeather trigger=$trigger flow=$flowId manualLocation=${Preferences.customLocationAdd != ""} " +
                "cachedLocation=${LocationHelper.hasCachedLocation()} cachedAgeMs=${LocationHelper.ageMs()}")

        when {
            // A manually configured location always wins and never touches the location providers.
            Preferences.customLocationAdd != "" -> {
                DebugLogger.d("LocationCache", "hit source=manual flow=$flowId")
                networkApi.updateWeather(flowId)
            }

            // Recent enough coordinates: refresh the weather without activating any location provider.
            LocationHelper.isCachedLocationFresh() -> {
                DebugLogger.d("LocationCache",
                    "hit ageMs=${LocationHelper.ageMs()} ttlMs=${Constants.LOCATION_CACHE_TTL} " +
                        "lat=${Preferences.customLocationLat} lon=${Preferences.customLocationLon} flow=$flowId")
                networkApi.updateWeather(flowId)
            }

            // Stale or missing coordinates: try to refresh them, falling back to the cached ones.
            context.checkGrantedPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                DebugLogger.d("WeatherHelper", "checkSelfPermission ACCESS_FINE_LOCATION=GRANTED flow=$flowId")
                DebugLogger.d("LocationCache",
                    "expired ageMs=${LocationHelper.ageMs()} ttlMs=${Constants.LOCATION_CACHE_TTL} flow=$flowId")
                DebugLogger.d("WeatherHelper", "location refresh delegated to LocationService flow=$flowId")
                LocationService.requestNewLocation(context, flowId)
            }

            // No permission, but an outdated coordinate still beats no weather at all.
            LocationHelper.hasCachedLocation() -> {
                DebugLogger.d("WeatherHelper", "checkSelfPermission ACCESS_FINE_LOCATION=DENIED flow=$flowId")
                DebugLogger.w("LocationCache",
                    "stale but location permission missing, reusing the stored coordinates " +
                        "ageMs=${LocationHelper.ageMs()} lat=${Preferences.customLocationLat} lon=${Preferences.customLocationLon} flow=$flowId")
                networkApi.updateWeather(flowId)
            }

            else -> {
                DebugLogger.d("WeatherHelper", "checkSelfPermission ACCESS_FINE_LOCATION=DENIED flow=$flowId")
                DebugLogger.w("LocationCache", "miss no cached location and no permission flow=$flowId")
                Preferences.weatherProviderLocationError = context.getString(R.string.weather_provider_error_missing_location)
                EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            }
        }
    }

    fun removeWeather(context: Context) {
        DebugLogger.d("WeatherRepository", "removing the displayed weather")
        Preferences.remove(Preferences::weatherTemp)
        Preferences.remove(Preferences::weatherRealTempUnit)
        Preferences.remove(Preferences::weatherIcon)
        MainWidget.updateWidget(context, "weather_removed")
    }

    fun getWeatherIconResource(context: Context, icon: String, style: Int = Preferences.weatherIconPack): Int {
        return when (icon) {
            "01d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.clear_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.clear_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.clear_day_4
                    else -> if (context.isDarkTheme()) R.drawable.clear_day_5 else R.drawable.clear_day_5_light
                }
            }
            "02d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.partly_cloudy_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.partly_cloudy_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.partly_cloudy_4
                    else -> if (context.isDarkTheme()) R.drawable.partly_cloudy_5 else R.drawable.partly_cloudy_5_light
                }
            }
            "03d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.mostly_cloudy_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.mostly_cloudy_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.mostly_cloudy_4
                    else -> if (context.isDarkTheme()) R.drawable.mostly_cloudy_5 else R.drawable.mostly_cloudy_5_light
                }
            }
            "04d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.cloudy_weather_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.cloudy_weather_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.cloudy_weather_4
                    else -> if (context.isDarkTheme()) R.drawable.cloudy_weather_5 else R.drawable.cloudy_weather_5_light
                }
            }
            "09d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.storm_weather_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.storm_weather_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.storm_weather_day_4
                    else -> if (context.isDarkTheme()) R.drawable.storm_weather_day_5 else R.drawable.storm_weather_day_5_light
                }
            }
            "10d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.rainy_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.rainy_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.rainy_day_4
                    else -> if (context.isDarkTheme()) R.drawable.rainy_day_5 else R.drawable.rainy_day_5_light
                }
            }
            "11d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.thunder_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.thunder_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.thunder_day_4
                    else -> if (context.isDarkTheme()) R.drawable.thunder_day_5 else R.drawable.thunder_day_5_light
                }
            }
            "13d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.snow_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.snow_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.snow_day_4
                    else -> if (context.isDarkTheme()) R.drawable.snow_day_5 else R.drawable.snow_day_5_light
                }
            }
            "50d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.haze_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.haze_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.haze_day_4
                    else -> if (context.isDarkTheme()) R.drawable.haze_day_5 else R.drawable.haze_day_5_light
                }
            }
            "80d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.windy_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.windy_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.windy_day_4
                    else -> if (context.isDarkTheme()) R.drawable.windy_day_5 else R.drawable.windy_day_5_light
                }
            }
            "81d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.rain_snow_day_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.rain_snow_day_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.rain_snow_day_4
                    else -> if (context.isDarkTheme()) R.drawable.rain_snow_day_5 else R.drawable.rain_snow_day_5_light
                }
            }
            "82d" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.haze_weather_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.haze_weather_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.haze_weather_4
                    else -> if (context.isDarkTheme()) R.drawable.haze_weather_5 else R.drawable.haze_weather_5_light
                }
            }



            "01n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.clear_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.clear_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.clear_night_4
                    else -> if (context.isDarkTheme()) R.drawable.clear_night_5 else R.drawable.clear_night_5_light
                }
            }
            "02n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.partly_cloudy_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.partly_cloudy_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.partly_cloudy_night_4
                    else -> if (context.isDarkTheme()) R.drawable.partly_cloudy_night_5 else R.drawable.partly_cloudy_night_5_light
                }
            }
            "03n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.mostly_cloudy_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.mostly_cloudy_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.mostly_cloudy_night_4
                    else -> if (context.isDarkTheme()) R.drawable.mostly_cloudy_night_5 else R.drawable.mostly_cloudy_night_5_light
                }
            }
            "04n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.cloudy_weather_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.cloudy_weather_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.cloudy_weather_4
                    else -> if (context.isDarkTheme()) R.drawable.cloudy_weather_5 else R.drawable.cloudy_weather_5_light
                }
            }
            "09n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.storm_weather_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.storm_weather_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.storm_weather_night_4
                    else -> if (context.isDarkTheme()) R.drawable.storm_weather_night_5 else R.drawable.storm_weather_night_5_light
                }
            }
            "10n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.rainy_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.rainy_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.rainy_night_4
                    else -> if (context.isDarkTheme()) R.drawable.rainy_night_5 else R.drawable.rainy_night_5_light
                }
            }
            "11n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.thunder_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.thunder_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.thunder_night_4
                    else -> if (context.isDarkTheme()) R.drawable.thunder_night_5 else R.drawable.thunder_night_5_light
                }
            }
            "13n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.snow_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.snow_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.snow_night_4
                    else -> if (context.isDarkTheme()) R.drawable.snow_night_5 else R.drawable.snow_night_5_light
                }
            }
            "50n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.haze_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.haze_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.haze_night_4
                    else -> if (context.isDarkTheme()) R.drawable.haze_night_5 else R.drawable.haze_night_5_light
                }
            }
            "80n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.windy_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.windy_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.windy_night_4
                    else -> if (context.isDarkTheme()) R.drawable.windy_night_5 else R.drawable.windy_night_5_light
                }
            }
            "81n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.rain_snow_night_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.rain_snow_night_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.rain_snow_night_4
                    else -> if (context.isDarkTheme()) R.drawable.rain_snow_night_5 else R.drawable.rain_snow_night_5_light
                }
            }
            "82n" -> {
                when (style) {
                    Constants.WeatherIconPack.COOL.rawValue -> R.drawable.haze_weather_3
                    Constants.WeatherIconPack.MINIMAL.rawValue -> R.drawable.haze_weather_2
                    Constants.WeatherIconPack.GOOGLE_NEWS.rawValue -> R.drawable.haze_weather_4
                    else -> if (context.isDarkTheme()) R.drawable.haze_weather_5 else R.drawable.haze_weather_5_light
                }
            }
            else -> {
                return if (context.isDarkTheme()) R.drawable.unknown_dark else R.drawable.unknown_light
            }
        }
    }

    /**
     * Maps a QWeather icon code to the internal icon identifier used by the icon packs. The 150-153,
     * 350, 351, 456 and 457 codes are the night variants of the condition they belong to; every
     * other condition only exists once, so the day/night art is picked from the current hour.
     */
    fun getQWeatherIcon(iconCode: String, isDaytime: Boolean): String {
        val code = iconCode.trim().toIntOrNull() ?: return UNKNOWN_ICON

        val suffix = when {
            code in 150..153 || code == 350 || code == 351 || code == 456 || code == 457 -> "n"
            isDaytime -> "d"
            else -> "n"
        }

        val base = when (code) {
            100, 150 -> "01"                 // Clear
            102, 103, 152, 153 -> "02"       // Few clouds / partly cloudy
            101, 151 -> "03"                 // Cloudy
            104 -> "04"                      // Overcast

            in 200..213 -> "80"              // Wind

            in 300..301, 350, 351 -> "09"                                // Shower rain
            in 305..306, 309, in 314..315, 399 -> "10"                   // Rain
            in 307..308, in 310..312, in 316..318 -> "09"                // Heavy rain
            in 302..304 -> "11"                                                // Thunderstorm
            313 -> "81"                                                        // Freezing rain

            in 400..403, in 407..410, 499 -> "13"  // Snow
            in 404..406, 456, 457 -> "81"          // Sleet / rain and snow

            500, 501, in 507..510, 515 -> "82"  // Fog
            in 502..504, in 511..514 -> "50"    // Haze
            505, 506 -> "50"                    // Dust / sand

            900 -> "01"                      // Hot
            901 -> "13"                      // Cold

            else -> return UNKNOWN_ICON
        }

        return base + suffix
    }

}