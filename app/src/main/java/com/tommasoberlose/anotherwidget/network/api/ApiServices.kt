package com.tommasoberlose.anotherwidget.network.api

import com.haroldadmin.cnradapter.NetworkResponse
import retrofit2.http.*

object ApiServices {
    interface QWeatherService {
        // Hourly forecast, QWeather Weather v1. Coordinates go in the path as latitude/longitude, the
        // credential as an "X-QW-Api-Key" header, plus the Android application-restriction headers
        // when the credential is restricted to this package and signing certificate.
        @GET("weather/v1/hourly/{latitude}/{longitude}")
        suspend fun getHourlyForecast(
            @Path("latitude") latitude: String,
            @Path("longitude") longitude: String,
            @Header("X-QW-Api-Key") apiKey: String,
            @HeaderMap headers: Map<String, String>,
            @Query("hours") hours: Int = 24,
            @Query("localTime") localTime: Boolean = true,
        ): NetworkResponse<HashMap<String, Any>, HashMap<String, Any>>

        // GeoAPI v2 city lookup: resolves coordinates to the stable location id that identifies the
        // weather region, so region changes never rely on display strings.
        @GET("geo/v2/city/lookup")
        suspend fun geoLookup(
            @Query("location") location: String,
            @Header("X-QW-Api-Key") apiKey: String,
            @HeaderMap headers: Map<String, String>,
            @Query("number") number: Int = 1,
            @Query("lang") lang: String = "zh",
        ): NetworkResponse<HashMap<String, Any>, HashMap<String, Any>>
    }

    interface TimeZonesService {
        @GET("timezoneJSON")
        suspend fun getTimeZone(
            @Query("lat") lat: String,
            @Query("lng") lon: String,
            @Query("username") username: String = "tommaso.berlose",
        ): NetworkResponse<HashMap<String, Any>, HashMap<String, Any>>
    }
}
