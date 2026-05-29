package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.util.PkceUtil
import uy.kohesive.injekt.injectLazy

/**
 * Generates and validates the OAuth 2.0 `state` parameter used to protect the tracker login
 * flow against CSRF / authorization-code injection. The tracker login deep-link callback is
 * exported, so without this any app or web page could deliver an attacker-controlled `code`
 * /`token` and bind the user's session to the attacker's account.
 *
 * Only one login flow runs at a time, so a single pending value is stored.
 */
object TrackerOAuthState {

    private val trackPreferences: TrackPreferences by injectLazy()

    /** Generates a new cryptographically-random state, persists it, and returns it. */
    fun create(): String {
        val state = PkceUtil.generateCodeVerifier()
        trackPreferences.trackAuthState().set(state)
        return state
    }

    /**
     * Returns true only if [returnedState] matches the pending state. The pending state is
     * cleared regardless so it can't be replayed.
     */
    fun consumeAndValidate(returnedState: String?): Boolean {
        val expected = trackPreferences.trackAuthState().get()
        trackPreferences.trackAuthState().delete()
        return expected.isNotEmpty() && expected == returnedState
    }
}
