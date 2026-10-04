package com.tommasoberlose.anotherwidget.helpers

/**
 * The decisions of the LocalLocationChange check, as plain values so the whole policy can be
 * verified without a device:
 *
 * manual location  -> SkipManual, always
 * manual refresh   -> RequestFresh (the user asked to re-confirm position AND weather)
 * otherwise        -> best usable last-known, else RequestFresh
 */
object LocationChangePolicy {

    sealed class Decision {
        object SkipManual : Decision()
        data class UseLastKnown(val candidate: LocationCandidate) : Decision()
        object RequestFresh : Decision()
    }

    fun decide(manualLocationEnabled: Boolean, manualRefresh: Boolean, candidates: List<LocationCandidate>): Decision = when {
        manualLocationEnabled -> Decision.SkipManual
        manualRefresh -> Decision.RequestFresh
        else -> LocationCandidates.bestUsable(candidates)
            ?.let { Decision.UseLastKnown(it) }
            ?: Decision.RequestFresh
    }

    /** After a failed fresh request: the persisted fallback (6h policy), or nothing at all. */
    fun afterFreshFailure(hasPersistedLocation: Boolean): Boolean = hasPersistedLocation

    /** The outcome of comparing the stored region id with the freshly resolved one. */
    sealed class RegionOutcome {
        /** Old and new region are the same: update the location cache, never the weather. */
        object Same : RegionOutcome()

        /** The user moved to another region: update cache and region, refresh with reason=location_changed. */
        object Changed : RegionOutcome()

        /** No previous region known (first run): adopt the region without forcing a refresh. */
        object Initial : RegionOutcome()

        /** The lookup failed: fall back to the movement distance heuristic, resolve again next hour. */
        object Unresolved : RegionOutcome()
    }

    fun compareRegions(oldRegionId: String, newRegionId: String): RegionOutcome = when {
        newRegionId.isBlank() -> RegionOutcome.Unresolved
        oldRegionId.isBlank() -> RegionOutcome.Initial
        oldRegionId == newRegionId -> RegionOutcome.Same
        else -> RegionOutcome.Changed
    }

    /**
     * QWeather requests carry the reason they happened. A TTL driven request that finds no cache at
     * all is really an initial load, which reads better in the log.
     */
    fun effectiveReason(requestedReason: String, cacheIsEmpty: Boolean): String =
        if (requestedReason == "weather_ttl" && cacheIsEmpty) "initial_load" else requestedReason
}
