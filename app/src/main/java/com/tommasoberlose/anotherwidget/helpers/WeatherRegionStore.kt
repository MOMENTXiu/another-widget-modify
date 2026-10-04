package com.tommasoberlose.anotherwidget.helpers

import android.location.Location
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import kotlin.math.abs

/**
 * The weather region identity: the stable QWeather GeoAPI location id (e.g. "101280101") of the area
 * the current weather belongs to, plus the coordinates of the last fix confirmed inside it.
 *
 * The fix coordinates act as the reuse anchor: as long as a new position stays within the region
 * reuse radius of the last confirmed fix, the region is reused without a lookup. Only a jump beyond
 * the radius costs one GeoAPI request per change - an hourly check is therefore essentially free.
 */
object WeatherRegionStore {

    data class StoredRegion(val id: String, val name: String, val anchorLat: Double, val anchorLon: Double)

    fun load(): StoredRegion? {
        val id = Preferences.weatherRegionId
        val lat = Preferences.weatherRegionLat.toDoubleOrNull()
        val lon = Preferences.weatherRegionLon.toDoubleOrNull()

        return if (id.isBlank() || lat == null || lon == null) null else StoredRegion(id, Preferences.weatherRegionName, lat, lon)
    }

    fun save(id: String, name: String, anchorLat: Double, anchorLon: Double) {
        Preferences.weatherRegionId = id
        Preferences.weatherRegionName = name
        Preferences.weatherRegionLat = anchorLat.toString()
        Preferences.weatherRegionLon = anchorLon.toString()
    }

    fun clear() {
        Preferences.weatherRegionId = ""
        Preferences.weatherRegionName = ""
        Preferences.weatherRegionLat = ""
        Preferences.weatherRegionLon = ""
    }

    /** metres between the last confirmed fix of the region and the given position. */
    fun distanceFromAnchor(region: StoredRegion, latitude: Double, longitude: Double): Float {
        val distance = FloatArray(1)
        Location.distanceBetween(region.anchorLat, region.anchorLon, latitude, longitude, distance)
        return distance[0]
    }

    /**
     * True when the position is close enough to the last confirmed fix that it can be assumed to sit
     * in the same region - the shortcut that keeps the hourly check lookup free.
     */
    fun isWithinReuseRadius(region: StoredRegion, latitude: Double, longitude: Double): Boolean =
        distanceFromAnchor(region, latitude, longitude) <= Constants.WEATHER_LOCATION_CHANGE_THRESHOLD

    /** Extracts the stable id from a GeoAPI city lookup body, or null when it has none. */
    fun idFromLookupBody(body: HashMap<String, Any>?): String? =
        ((body?.get("location") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("id") as? String

    /** Human readable name of the first lookup result, for the debug log only. */
    fun nameFromLookupBody(body: HashMap<String, Any>?): String =
        ((body?.get("location") as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.let { entry -> listOfNotNull(entry["adm2"] as? String, entry["name"] as? String).joinToString(" ") }
            .orEmpty()

    /** Sanity guard used by tests: the anchor must never drift by a whole world turn. */
    fun isPlausible(latitude: Double, longitude: Double): Boolean =
        abs(latitude) <= 90.0 && abs(longitude) <= 180.0
}
