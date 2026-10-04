package com.tommasoberlose.anotherwidget.helpers

import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences

/**
 * Cache policy for the last known coordinate.
 *
 * The timestamp is only the age of the fix: reaching the TTL means the location should be refreshed,
 * it never makes the stored coordinates unusable. An outdated coordinate is always preferable to
 * losing the weather.
 */
object LocationHelper {

    fun hasCachedLocation(): Boolean =
        Preferences.customLocationLat != "" && Preferences.customLocationLon != ""

    fun isCachedLocationFresh(): Boolean =
        hasCachedLocation() && System.currentTimeMillis() - Preferences.lastLocationTimestamp <= Constants.LOCATION_CACHE_TTL

    /** For the logs: how old the stored fix is, without leaking coordinates. */
    fun describeAge(): String {
        if (!hasCachedLocation()) return "no cached location"

        val minutes = (System.currentTimeMillis() - Preferences.lastLocationTimestamp) / 60_000
        return "age=${minutes}m"
    }

    fun saveLocation(latitude: Double, longitude: Double, fixTime: Long) {
        Preferences.customLocationLat = latitude.toString()
        Preferences.customLocationLon = longitude.toString()
        Preferences.lastLocationTimestamp = if (fixTime > 0) fixTime else System.currentTimeMillis()
    }
}
