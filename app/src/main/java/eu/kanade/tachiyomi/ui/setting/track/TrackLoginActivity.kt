package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.TrackerOAuthState
import tachiyomi.core.common.util.lang.launchIO

class TrackLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(data: Uri?) {
        when (data?.host) {
            "anilist-auth" -> handleAnilist(data)
            "bangumi-auth" -> handleBangumi(data)
            "myanimelist-auth" -> handleMyAnimeList(data)
            "shikimori-auth" -> handleShikimori(data)
            "simkl-auth" -> handleSimkl(data)
        }
    }

    private fun handleAnilist(data: Uri) {
        val fragment = data.fragment.orEmpty()
        val token = "(?:access_token=)(.*?)(?:&)".toRegex().find(fragment)?.groups?.get(1)?.value
        val state = "(?:state=)(.*?)(?:&|$)".toRegex().find(fragment)?.groups?.get(1)?.value
        // Reject any callback whose state doesn't match the one we generated (CSRF / token injection).
        if (token != null && TrackerOAuthState.consumeAndValidate(state)) {
            lifecycleScope.launchIO {
                trackerManager.aniList.login(token)
                returnToSettings()
            }
        } else {
            trackerManager.aniList.logout()
            returnToSettings()
        }
    }

    private fun handleBangumi(data: Uri) {
        val code = data.getQueryParameter("code")
        if (code != null && TrackerOAuthState.consumeAndValidate(data.getQueryParameter("state"))) {
            lifecycleScope.launchIO {
                trackerManager.bangumi.login(code)
                returnToSettings()
            }
        } else {
            trackerManager.bangumi.logout()
            returnToSettings()
        }
    }

    private fun handleMyAnimeList(data: Uri) {
        val code = data.getQueryParameter("code")
        if (code != null && TrackerOAuthState.consumeAndValidate(data.getQueryParameter("state"))) {
            lifecycleScope.launchIO {
                trackerManager.myAnimeList.login(code)
                returnToSettings()
            }
        } else {
            trackerManager.myAnimeList.logout()
            returnToSettings()
        }
    }

    private fun handleShikimori(data: Uri) {
        val code = data.getQueryParameter("code")
        if (code != null && TrackerOAuthState.consumeAndValidate(data.getQueryParameter("state"))) {
            lifecycleScope.launchIO {
                trackerManager.shikimori.login(code)
                returnToSettings()
            }
        } else {
            trackerManager.shikimori.logout()
            returnToSettings()
        }
    }

    private fun handleSimkl(data: Uri?) {
        val code = data?.getQueryParameter("code")
        if (code != null && TrackerOAuthState.consumeAndValidate(data.getQueryParameter("state"))) {
            lifecycleScope.launchIO {
                trackerManager.simkl.login(code)
                returnToSettings()
            }
        } else {
            trackerManager.simkl.logout()
            returnToSettings()
        }
    }
}
