package com.tommasoberlose.anotherwidget

import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.helpers.LocationCandidate
import com.tommasoberlose.anotherwidget.helpers.LocationChangePolicy
import com.tommasoberlose.anotherwidget.helpers.LocationCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LocalLocationChange policy: last-known acceptance, fresh request fallback, region comparison
 * and the reason a QWeather request carries. All pure, no device needed.
 */
class LocationChangeLogicTest {

    private val now = System.currentTimeMillis()

    private fun candidate(provider: String, ageMinutes: Long, accuracy: Float) =
        LocationCandidate(provider, 23.13, 113.26, accuracy, ageMinutes * 60_000)

    // --- 1: manual location skips the automatic check ---

    @Test
    fun `manual location mode skips the location change check`() {
        val decision = LocationChangePolicy.decide(
            manualLocationEnabled = true, manualRefresh = true,
            candidates = listOf(candidate("network", 1, 30f))
        )

        assertTrue(decision is LocationChangePolicy.Decision.SkipManual)
    }

    // --- 2 & 3: fresh and accurate last-known positions are accepted ---

    @Test
    fun `network last-known ten minutes old at thirty meters is accepted`() {
        val network = candidate("network", 10, 30f)

        assertTrue(LocationCandidates.usable(network))
        assertTrue(LocationCandidates.bestUsable(listOf(network)) == network)
    }

    @Test
    fun `passive last-known five minutes old at one hundred meters is accepted`() {
        val passive = candidate("passive", 5, 100f)

        assertTrue(LocationCandidates.usable(passive))
        assertTrue(LocationCandidates.bestUsable(listOf(passive)) == passive)
    }

    // --- 4 & 5: stale or inaccurate positions are rejected with a reason ---

    @Test
    fun `gps last-known four hours old is rejected because it is stale`() {
        val gps = candidate("gps", 4 * 60, 5f)

        assertFalse(LocationCandidates.usable(gps))
        assertEquals("too_old", LocationCandidates.rejectionReason(gps))
    }

    @Test
    fun `network last-known five minutes old at two kilometers is rejected because it is inaccurate`() {
        val network = candidate("network", 5, 2_000f)

        assertFalse(LocationCandidates.usable(network))
        assertEquals("inaccurate", LocationCandidates.rejectionReason(network))
    }

    // --- 6: among several candidates freshness wins, accuracy breaks ties ---

    @Test
    fun `the freshest usable candidate wins over the more accurate but older one`() {
        val network = candidate("network", 10, 30f)
        val gps = candidate("gps", 25, 6f)

        assertEquals(network, LocationCandidates.bestUsable(listOf(network, gps)))
    }

    @Test
    fun `equal age goes to the more accurate candidate`() {
        val network = candidate("network", 10, 30f)
        val fused = candidate("fused", 10, 15f)

        assertEquals(fused, LocationCandidates.bestUsable(listOf(network, fused)))
    }

    @Test
    fun `every candidate carries an accept or reject verdict for the debug log`() {
        val verdicts = LocationCandidates.judge(
            listOf(candidate("network", 10, 30f), candidate("gps", 240, 5f))
        ).second

        assertEquals("fresh_and_accurate", verdicts[0].reason)
        assertTrue(verdicts[0].accepted)
        assertEquals("too_old", verdicts[1].reason)
        assertFalse(verdicts[1].accepted)
    }

    // --- 7 & 8: no usable last-known means a fresh request, whose failure falls back ---

    @Test
    fun `without a usable last-known the policy asks for a fresh network fix`() {
        val decision = LocationChangePolicy.decide(
            manualLocationEnabled = false, manualRefresh = false,
            candidates = listOf(candidate("gps", 240, 5f), candidate("network", 5, 2_000f))
        )

        assertTrue(decision is LocationChangePolicy.Decision.RequestFresh)
    }

    @Test
    fun `a scheduled check uses a usable last-known instead of requesting a fix`() {
        val decision = LocationChangePolicy.decide(
            manualLocationEnabled = false, manualRefresh = false,
            candidates = listOf(candidate("network", 10, 30f))
        )

        assertTrue(decision is LocationChangePolicy.Decision.UseLastKnown)
    }

    @Test
    fun `a failed fresh request falls back to the persisted location when it exists`() {
        assertTrue(LocationChangePolicy.afterFreshFailure(hasPersistedLocation = true))
        assertFalse(LocationChangePolicy.afterFreshFailure(hasPersistedLocation = false))
    }

    @Test
    fun `a fresh fix above the accuracy ceiling is rejected`() {
        assertTrue(LocationCandidates.freshFixUsable(30f))
        assertFalse(LocationCandidates.freshFixUsable(2_500f))
    }

    // --- 12: manual refresh always re-confirms the position with a fresh fix ---

    @Test
    fun `manual refresh requests a fresh fix even with a usable last-known`() {
        val decision = LocationChangePolicy.decide(
            manualLocationEnabled = false, manualRefresh = true,
            candidates = listOf(candidate("network", 1, 30f))
        )

        assertTrue(decision is LocationChangePolicy.Decision.RequestFresh)
    }

    // --- 9, 10: the region comparison decides whether weather refreshes ---

    @Test
    fun `the same region never triggers a weather refresh`() {
        assertTrue(LocationChangePolicy.compareRegions("101280110", "101280110") is LocationChangePolicy.RegionOutcome.Same)
    }

    @Test
    fun `a different region triggers the location_changed refresh`() {
        val outcome = LocationChangePolicy.compareRegions("101280110", "101280103")

        assertTrue(outcome is LocationChangePolicy.RegionOutcome.Changed)
    }

    @Test
    fun `the first resolved region is adopted without forcing a refresh`() {
        assertTrue(LocationChangePolicy.compareRegions("", "101280110") is LocationChangePolicy.RegionOutcome.Initial)
    }

    @Test
    fun `a failed lookup is unresolved and never counts as a region change`() {
        assertTrue(LocationChangePolicy.compareRegions("101280110", "") is LocationChangePolicy.RegionOutcome.Unresolved)
    }

    // --- 11 & 22: QWeather request reasons ---

    @Test
    fun `a ttl refresh with an empty cache is reported as the initial load`() {
        assertEquals("initial_load", LocationChangePolicy.effectiveReason("weather_ttl", cacheIsEmpty = true))
    }

    @Test
    fun `a ttl refresh with a cache keeps the weather_ttl reason`() {
        assertEquals("weather_ttl", LocationChangePolicy.effectiveReason("weather_ttl", cacheIsEmpty = false))
    }

    @Test
    fun `location changes and manual refreshes keep their own reason`() {
        assertEquals("location_changed", LocationChangePolicy.effectiveReason("location_changed", cacheIsEmpty = true))
        assertEquals("manual_refresh", LocationChangePolicy.effectiveReason("manual_refresh", cacheIsEmpty = false))
    }

    // --- 18 & 19: the scheduling and fallback constants keep their meaning ---

    @Test
    fun `the location change check is scheduled about hourly`() {
        assertEquals(60 * 60 * 1000L, Constants.LOCATION_CHECK_INTERVAL_MS)
    }

    @Test
    fun `the persisted location fallback ttl is still six hours, not the 30 minute window`() {
        assertEquals(6 * 60 * 60 * 1000L, Constants.LOCATION_CACHE_TTL)
        assertEquals(30 * 60 * 1000L, Constants.LOCATION_LAST_KNOWN_MAX_AGE_MS)
        assertTrue(Constants.LOCATION_LAST_KNOWN_MAX_AGE_MS < Constants.LOCATION_CACHE_TTL)
    }

    // --- 16 & 17: the default path never races providers, fresh is network only ---

    @Test
    fun `the fresh fix ceiling leaves no room for a gps race`() {
        // The fresh request is network only with a 5s timeout; gps and fused exist as last-known
        // candidates only. These constants document that contract.
        assertEquals(5 * 1000L, Constants.LOCATION_FRESH_TIMEOUT_MS)
        assertEquals(2_000f, Constants.LOCATION_FRESH_MAX_ACCURACY_M)
    }

    @Test
    fun `a candidate list containing only rejects yields no selection`() {
        assertNull(LocationCandidates.bestUsable(emptyList()))
        assertNull(LocationCandidates.bestUsable(listOf(candidate("gps", 240, 5f))))
    }
}
