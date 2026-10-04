package com.tommasoberlose.anotherwidget.helpers

import com.tommasoberlose.anotherwidget.global.Preferences

/**
 * One preference that belongs in a backup: how to read it, how to write it, and what counts as a
 * value worth writing back.
 */
class BackupEntry<T : Any>(
    val name: String,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val coerce: (Any?) -> T?
) {
    fun current(): Any = read()

    fun accept(raw: Any?): T? = coerce(raw)

    @Suppress("UNCHECKED_CAST")
    fun apply(value: Any) = write(value as T)
}

private val COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
private val ALPHA = Regex("^[0-9a-fA-F]{2}$")

private fun boolean(name: String, read: () -> Boolean, write: (Boolean) -> Unit) =
    BackupEntry(name, read, write) { it as? Boolean }

private fun int(name: String, read: () -> Int, write: (Int) -> Unit, valid: (Int) -> Boolean = { true }) =
    BackupEntry(name, read, write) { raw ->
        // JSON numbers arrive as Double, so only integral values are accepted.
        (raw as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toInt()?.takeIf(valid)
    }

private fun long(name: String, read: () -> Long, write: (Long) -> Unit, valid: (Long) -> Boolean = { true }) =
    BackupEntry(name, read, write) { raw ->
        (raw as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()?.takeIf(valid)
    }

private fun float(name: String, read: () -> Float, write: (Float) -> Unit, valid: (Float) -> Boolean = { true }) =
    BackupEntry(name, read, write) { raw -> (raw as? Number)?.toFloat()?.takeIf { !it.isNaN() && valid(it) } }

private fun string(name: String, read: () -> String, write: (String) -> Unit, valid: (String) -> Boolean = { true }) =
    BackupEntry(name, read, write) { raw -> (raw as? String)?.takeIf(valid) }

private fun color(name: String, read: () -> String, write: (String) -> Unit) =
    string(name, read, write) { COLOR.matches(it) }

private fun alpha(name: String, read: () -> String, write: (String) -> Unit) =
    string(name, read, write) { ALPHA.matches(it) }

private fun text(name: String, read: () -> String, write: (String) -> Unit, maxLength: Int = 256) =
    string(name, read, write) { it.length <= maxLength }

private fun coordinate(name: String, read: () -> String, write: (String) -> Unit, limit: Double) =
    string(name, read, write) { raw -> raw.toDoubleOrNull()?.let { it >= -limit && it <= limit } ?: false }

private fun range(from: Int, to: Int): (Int) -> Boolean = { it in from..to }

/**
 * The audited list of what a backup contains.
 *
 * Deliberately absent, and why:
 *  - weatherProviderApiQWeather: a secret. Credentials are provisioned separately, never exported.
 *  - weatherForecastCache, weatherIcon, weatherTemp, weatherRealTempUnit, lastLocationTimestamp,
 *    weatherProviderError, weatherProviderLocationError: weather state that is regenerated.
 *  - nextEvent*, lastNotification*, mediaPlayer*: transient runtime snapshots.
 *  - isBatteryLevelLow, isCharging, googleFitSteps: sensor state.
 *  - customFontFile: names a downloaded font file inside this installation; the file itself cannot
 *    travel in a JSON backup.
 *  - installedIntegrations: records a purchase on this account, not a user preference.
 *
 * The manual location is the exception worth knowing about: [MANUAL_COORDINATES] are only exported
 * while a manual location is configured, because otherwise those same fields hold the automatic
 * location cache.
 */
object BackupSchema {

    val MANUAL_COORDINATES = setOf("customLocationLat", "customLocationLon")

    val entries: List<BackupEntry<*>> = listOf(
        // Appearance
        int("darkThemePreference", { Preferences.darkThemePreference }, { Preferences.darkThemePreference = it }) { it in intArrayOf(-1, 0, 1, 2) },
        boolean("showWallpaper", { Preferences.showWallpaper }, { Preferences.showWallpaper = it }),
        boolean("showPreview", { Preferences.showPreview }, { Preferences.showPreview = it }),
        boolean("showXiaomiWarning", { Preferences.showXiaomiWarning }, { Preferences.showXiaomiWarning = it }),
        color("textGlobalColor", { Preferences.textGlobalColor }, { Preferences.textGlobalColor = it }),
        alpha("textGlobalAlpha", { Preferences.textGlobalAlpha }, { Preferences.textGlobalAlpha = it }),
        color("textSecondaryColor", { Preferences.textSecondaryColor }, { Preferences.textSecondaryColor = it }),
        alpha("textSecondaryAlpha", { Preferences.textSecondaryAlpha }, { Preferences.textSecondaryAlpha = it }),
        color("backgroundCardColor", { Preferences.backgroundCardColor }, { Preferences.backgroundCardColor = it }),
        alpha("backgroundCardAlpha", { Preferences.backgroundCardAlpha }, { Preferences.backgroundCardAlpha = it }),
        color("clockTextColor", { Preferences.clockTextColor }, { Preferences.clockTextColor = it }),
        alpha("clockTextAlpha", { Preferences.clockTextAlpha }, { Preferences.clockTextAlpha = it }),
        color("textGlobalColorDark", { Preferences.textGlobalColorDark }, { Preferences.textGlobalColorDark = it }),
        alpha("textGlobalAlphaDark", { Preferences.textGlobalAlphaDark }, { Preferences.textGlobalAlphaDark = it }),
        color("textSecondaryColorDark", { Preferences.textSecondaryColorDark }, { Preferences.textSecondaryColorDark = it }),
        alpha("textSecondaryAlphaDark", { Preferences.textSecondaryAlphaDark }, { Preferences.textSecondaryAlphaDark = it }),
        color("backgroundCardColorDark", { Preferences.backgroundCardColorDark }, { Preferences.backgroundCardColorDark = it }),
        alpha("backgroundCardAlphaDark", { Preferences.backgroundCardAlphaDark }, { Preferences.backgroundCardAlphaDark = it }),
        color("clockTextColorDark", { Preferences.clockTextColorDark }, { Preferences.clockTextColorDark = it }),
        alpha("clockTextAlphaDark", { Preferences.clockTextAlphaDark }, { Preferences.clockTextAlphaDark = it }),
        boolean("showAMPMIndicator", { Preferences.showAMPMIndicator }, { Preferences.showAMPMIndicator = it }),
        boolean("showDividers", { Preferences.showDividers }, { Preferences.showDividers = it }),
        int("widgetAlign", { Preferences.widgetAlign }, { Preferences.widgetAlign = it }, range(0, 2)),
        int("weatherIconPack", { Preferences.weatherIconPack }, { Preferences.weatherIconPack = it }, range(0, 3)),

        // Typography
        float("textMainSize", { Preferences.textMainSize }, { Preferences.textMainSize = it }) { it in 8f..200f },
        float("textSecondSize", { Preferences.textSecondSize }, { Preferences.textSecondSize = it }) { it in 8f..200f },
        float("clockTextSize", { Preferences.clockTextSize }, { Preferences.clockTextSize = it }) { it in 20f..300f },
        int("clockBottomMargin", { Preferences.clockBottomMargin }, { Preferences.clockBottomMargin = it }, range(0, 3)),
        int("secondRowTopMargin", { Preferences.secondRowTopMargin }, { Preferences.secondRowTopMargin = it }, range(0, 3)),
        int("textShadow", { Preferences.textShadow }, { Preferences.textShadow = it }, range(0, 2)),
        int("textShadowDark", { Preferences.textShadowDark }, { Preferences.textShadowDark = it }, range(0, 2)),
        int("customFont", { Preferences.customFont }, { Preferences.customFont = it }, range(1, 3)),
        text("customFontName", { Preferences.customFontName }, { Preferences.customFontName = it }, 128),
        text("customFontVariant", { Preferences.customFontVariant }, { Preferences.customFontVariant = it }, 32),
        text("dateFormat", { Preferences.dateFormat }, { Preferences.dateFormat = it }, 128),
        boolean("isDateCapitalize", { Preferences.isDateCapitalize }, { Preferences.isDateCapitalize = it }),
        boolean("isDateUppercase", { Preferences.isDateUppercase }, { Preferences.isDateUppercase = it }),

        // Clock, second row and widget behaviour
        boolean("showClock", { Preferences.showClock }, { Preferences.showClock = it }),
        boolean("showDiffTime", { Preferences.showDiffTime }, { Preferences.showDiffTime = it }),
        int("widgetUpdateFrequency", { Preferences.widgetUpdateFrequency }, { Preferences.widgetUpdateFrequency = it }, range(0, 2)),
        int("secondRowInformation", { Preferences.secondRowInformation }, { Preferences.secondRowInformation = it }, range(0, 1)),
        text("clockAppName", { Preferences.clockAppName }, { Preferences.clockAppName = it }),
        text("clockAppPackage", { Preferences.clockAppPackage }, { Preferences.clockAppPackage = it }),
        text("altTimezoneLabel", { Preferences.altTimezoneLabel }, { Preferences.altTimezoneLabel = it }, 128),
        text("altTimezoneId", { Preferences.altTimezoneId }, { Preferences.altTimezoneId = it }, 128),

        // Calendar and events
        boolean("showEvents", { Preferences.showEvents }, { Preferences.showEvents = it }),
        boolean("calendarAllDay", { Preferences.calendarAllDay }, { Preferences.calendarAllDay = it }),
        text("calendarFilter", { Preferences.calendarFilter }, { Preferences.calendarFilter = it }, 4096),
        boolean("showDeclinedEvents", { Preferences.showDeclinedEvents }, { Preferences.showDeclinedEvents = it }),
        boolean("showInvitedEvents", { Preferences.showInvitedEvents }, { Preferences.showInvitedEvents = it }),
        boolean("showAcceptedEvents", { Preferences.showAcceptedEvents }, { Preferences.showAcceptedEvents = it }),
        boolean("showOnlyBusyEvents", { Preferences.showOnlyBusyEvents }, { Preferences.showOnlyBusyEvents = it }),
        boolean("showNextEvent", { Preferences.showNextEvent }, { Preferences.showNextEvent = it }),
        boolean("showNextEventOnMultipleLines", { Preferences.showNextEventOnMultipleLines }, { Preferences.showNextEventOnMultipleLines = it }),
        boolean("openEventDetails", { Preferences.openEventDetails }, { Preferences.openEventDetails = it }),
        int("showUntil", { Preferences.showUntil }, { Preferences.showUntil = it }, range(0, 7)),
        text("calendarAppName", { Preferences.calendarAppName }, { Preferences.calendarAppName = it }),
        text("calendarAppPackage", { Preferences.calendarAppPackage }, { Preferences.calendarAppPackage = it }),
        text("eventAppName", { Preferences.eventAppName }, { Preferences.eventAppName = it }),
        text("eventAppPackage", { Preferences.eventAppPackage }, { Preferences.eventAppPackage = it }),

        // Weather
        boolean("showWeather", { Preferences.showWeather }, { Preferences.showWeather = it }),
        string("weatherTempUnit", { Preferences.weatherTempUnit }, { Preferences.weatherTempUnit = it }) { it == "C" || it == "F" },
        int("weatherRefreshPeriod", { Preferences.weatherRefreshPeriod }, { Preferences.weatherRefreshPeriod = it }, range(0, 5)),
        text("weatherAppName", { Preferences.weatherAppName }, { Preferences.weatherAppName = it }),
        text("weatherAppPackage", { Preferences.weatherAppPackage }, { Preferences.weatherAppPackage = it }),
        text("weatherProviderQWeatherHost", { Preferences.weatherProviderQWeatherHost }, { Preferences.weatherProviderQWeatherHost = it }, 253),
        boolean("weatherProviderQWeatherAndroidRestriction", { Preferences.weatherProviderQWeatherAndroidRestriction }, { Preferences.weatherProviderQWeatherAndroidRestriction = it }),

        // Location: the manual coordinates are configuration, the automatic cache is not.
        text("customLocationAdd", { Preferences.customLocationAdd }, { Preferences.customLocationAdd = it }),
        coordinate("customLocationLat", { Preferences.customLocationLat }, { Preferences.customLocationLat = it }, 90.0),
        coordinate("customLocationLon", { Preferences.customLocationLon }, { Preferences.customLocationLon = it }, 180.0),

        // Glance rows
        text("enabledGlanceProviderOrder", { Preferences.enabledGlanceProviderOrder }, { Preferences.enabledGlanceProviderOrder = it }, 512),
        text("customNotes", { Preferences.customNotes }, { Preferences.customNotes = it }, 4096),
        boolean("showNextAlarm", { Preferences.showNextAlarm }, { Preferences.showNextAlarm = it }),
        boolean("showBatteryCharging", { Preferences.showBatteryCharging }, { Preferences.showBatteryCharging = it }),
        boolean("showDailySteps", { Preferences.showDailySteps }, { Preferences.showDailySteps = it }),
        boolean("showGreetings", { Preferences.showGreetings }, { Preferences.showGreetings = it }),
        boolean("showNotifications", { Preferences.showNotifications }, { Preferences.showNotifications = it }),
        int("hideNotificationAfter", { Preferences.hideNotificationAfter }, { Preferences.hideNotificationAfter = it }, range(0, 5)),
        boolean("showMusic", { Preferences.showMusic }, { Preferences.showMusic = it }),
        text("mediaInfoFormat", { Preferences.mediaInfoFormat }, { Preferences.mediaInfoFormat = it }, 512),
        text("musicPlayersFilter", { Preferences.musicPlayersFilter }, { Preferences.musicPlayersFilter = it }, 4096),
        text("appNotificationsFilter", { Preferences.appNotificationsFilter }, { Preferences.appNotificationsFilter = it }, 4096),
        boolean("showEventsAsGlanceProvider", { Preferences.showEventsAsGlanceProvider }, { Preferences.showEventsAsGlanceProvider = it })
    )
}
