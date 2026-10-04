package com.tommasoberlose.anotherwidget.global

object Constants {
    // Single tag for the weather/location decision path, so the whole sequence can be followed with
    // one filter. Credentials are never logged.
    const val LOG_TAG = "QWeather"

    const val RESULT_CODE_CUSTOM_LOCATION = 45
    const val RESULT_APP_NAME = "RESULT_APP_NAME"
    const val RESULT_APP_PACKAGE = "RESULT_APP_PACKAGE"

    // A cached location older than this is refreshed before the next weather request. It only
    // triggers a location refresh: an older coordinate stays usable, see LocationHelper.
    const val LOCATION_CACHE_TTL = 6 * 60 * 60 * 1000L

    // Upper bound for a single location fix, so the foreground service never waits on GPS forever.
    const val LOCATION_ACQUISITION_TIMEOUT = 15 * 1000L

    // The widget advances every hour from the cached hourly forecast; a fresh forecast is only
    // requested once the cache is older than this.
    const val WEATHER_CACHE_TTL = 3 * 60 * 60 * 1000L

    // A cached hour further from "now" than this is not displayed: an outdated forecast must not be
    // presented as the current weather.
    const val WEATHER_FORECAST_MAX_DRIFT = 3 * 60 * 60 * 1000L

    // Moving further than this from where the forecast was fetched makes it useless (weather is a
    // city scale quantity); normal GPS jitter stays far below it.
    const val WEATHER_LOCATION_CHANGE_THRESHOLD = 10_000f

    const val CUSTOM_FONT_GOOGLE_SANS = 1
    const val CUSTOM_FONT_DOWNLOADED = 2
    const val CUSTOM_FONT_DOWNLOAD_NEW = 3

    enum class ClockBottomMargin(val rawValue: Int) {
        NONE(0),
        SMALL(1),
        MEDIUM(2),
        LARGE(3)
    }

    enum class SecondRowTopMargin(val rawValue: Int) {
        NONE(0),
        SMALL(1),
        MEDIUM(2),
        LARGE(3)
    }

    enum class GlanceProviderId(val id: String) {
        PLAYING_SONG("PLAYING_SONG"),
        NEXT_CLOCK_ALARM("NEXT_CLOCK_ALARM"),
        BATTERY_LEVEL_LOW("BATTERY_LEVEL_LOW"),
        CUSTOM_INFO("CUSTOM_INFO"),
        GOOGLE_FIT_STEPS("GOOGLE_FIT_STEPS"),
        NOTIFICATIONS("NOTIFICATIONS"),
        GREETINGS("GREETINGS"),
        EVENTS("EVENTS");

        companion object {
            private val map = GlanceProviderId.values().associateBy(GlanceProviderId::id)
            fun from(type: String) = map[type]
        }
    }

    enum class WidgetUpdateFrequency(val rawValue: Int) {
        LOW(0),
        DEFAULT(1),
        HIGH(2)
    }

    enum class GlanceNotificationTimer(val rawValue: Int) {
        HALF_MINUTE(0),
        ONE_MINUTE(1),
        FIVE_MINUTES(2),
        TEN_MINUTES(3),
        FIFTEEN_MINUTES(4),
        WHEN_DISMISSED(5);

        companion object {
            private val map = values().associateBy(GlanceNotificationTimer::rawValue)
            fun fromInt(type: Int) = map[type]
        }
    }

    enum class WeatherIconPack(val rawValue: Int) {
        DEFAULT(0),
        MINIMAL(1),
        COOL(2),
        GOOGLE_NEWS(3)
    }

    enum class WidgetAlign(val rawValue: Int) {
        LEFT(0),
        RIGHT(1),
        CENTER(2)
    }
}