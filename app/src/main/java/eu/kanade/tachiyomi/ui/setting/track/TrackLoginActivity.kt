package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerOAuthState
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.aniyomi.AYMR

class TrackLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(data: Uri?) {
        when (data?.host) {
            "anilist-auth" -> handleAnilist(data)
            "bangumi-auth" -> handleBangumi(data)
            "myanimelist-auth" -> handleMyAnimeList(data)
            "shikimori-auth" -> handleShikimori(data)
            "simkl-auth" -> handleSimkl(data)
            else -> returnToSettings()
        }
    }

    private fun handleAnilist(data: Uri) {
        val fragment = data.fragment.orEmpty()
        val token = "(?:access_token=)(.*?)(?:&)".toRegex().find(fragment)?.groups?.get(1)?.value
        val state = "(?:state=)(.*?)(?:&|$)".toRegex().find(fragment)?.groups?.get(1)?.value
        completeLogin(trackerManager.aniList, token, state) { trackerManager.aniList.login(it) }
    }

    private fun handleBangumi(data: Uri) {
        completeLogin(trackerManager.bangumi, data) { trackerManager.bangumi.login(it) }
    }

    private fun handleMyAnimeList(data: Uri) {
        completeLogin(trackerManager.myAnimeList, data) { trackerManager.myAnimeList.login(it) }
    }

    private fun handleShikimori(data: Uri) {
        completeLogin(trackerManager.shikimori, data) { trackerManager.shikimori.login(it) }
    }

    private fun handleSimkl(data: Uri) {
        completeLogin(trackerManager.simkl, data) { trackerManager.simkl.login(it) }
    }

    private fun completeLogin(tracker: Tracker, data: Uri, login: suspend (String) -> Unit) {
        completeLogin(tracker, data.getQueryParameter("code"), data.getQueryParameter("state"), login)
    }

    /**
     * This activity is exported, so any app or web page can open it. A callback that doesn't carry
     * the state of the login we started must therefore leave the current session untouched; only
     * a validated callback may change the tracker's login.
     */
    private fun completeLogin(tracker: Tracker, credential: String?, state: String?, login: suspend (String) -> Unit) {
        if (credential == null || !TrackerOAuthState.consumeAndValidate(state)) {
            toast(AYMR.strings.track_login_failed)
            returnToSettings()
            return
        }
        lifecycleScope.launchIO {
            login(credential)
            withUIContext {
                if (!tracker.isLoggedIn) toast(AYMR.strings.track_login_failed)
                returnToSettings()
            }
        }
    }
}
