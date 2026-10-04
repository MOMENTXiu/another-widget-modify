package com.tommasoberlose.anotherwidget.helpers

import com.tommasoberlose.anotherwidget.global.Constants

/**
 * One system-provided position reading, ready to be judged for weather use. Weather is a region
 * scale quantity, so a 30m fix from ten minutes ago beats a 5m fix from four hours ago.
 */
data class LocationCandidate(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val ageMs: Long
)

/** Why a candidate was accepted or rejected; every decision ends up in the debug log. */
data class CandidateVerdict(val candidate: LocationCandidate?, val accepted: Boolean, val reason: String)

/**
 * The first-version acceptance policy for last-known positions, kept as plain testable functions:
 * age <= 30 min AND accuracy <= 500 m makes a position usable for weather. Among the usable ones
 * freshness wins, accuracy only breaks ties - no scoring framework.
 */
object LocationCandidates {

    fun usable(candidate: LocationCandidate): Boolean = when {
        candidate.ageMs > Constants.LOCATION_LAST_KNOWN_MAX_AGE_MS -> false
        candidate.accuracy > Constants.LOCATION_LAST_KNOWN_MAX_ACCURACY_M -> false
        else -> true
    }

    fun rejectionReason(candidate: LocationCandidate): String = when {
        candidate.ageMs > Constants.LOCATION_LAST_KNOWN_MAX_AGE_MS -> "too_old"
        candidate.accuracy > Constants.LOCATION_LAST_KNOWN_MAX_ACCURACY_M -> "inaccurate"
        else -> "accepted"
    }

    /** Freshest usable candidate; ties go to the more accurate one. */
    fun bestUsable(candidates: List<LocationCandidate>): LocationCandidate? =
        candidates.asSequence()
            .filter { usable(it) }
            .minWithOrNull(compareBy({ it.ageMs }, { it.accuracy }))

    /** Judges every candidate so the debug log can show accept/reject per provider. */
    fun judge(candidates: List<LocationCandidate>): Pair<CandidateVerdict?, List<CandidateVerdict>> {
        val judged = candidates.map { candidate ->
            CandidateVerdict(candidate, usable(candidate), if (usable(candidate)) "fresh_and_accurate" else rejectionReason(candidate))
        }

        val best = bestUsable(candidates)
        val selected = judged.firstOrNull { it.candidate == best }

        return selected to judged
    }

    /** A fresh fix only has an accuracy ceiling; freshness is implied by being fresh. */
    fun freshFixUsable(accuracy: Float): Boolean = accuracy <= Constants.LOCATION_FRESH_MAX_ACCURACY_M
}
