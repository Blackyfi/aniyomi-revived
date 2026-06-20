package eu.kanade.domain.extension.manga.interactor

import android.content.pm.PackageInfo
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet

class TrustMangaExtension(
    private val mangaExtensionRepoRepository: MangaExtensionRepoRepository,
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        // Deny-by-default: an extension is trusted IFF its signing-cert fingerprint matches a
        // configured repo's signingKeyFingerprint. The per-extension manual "trust anyway"
        // override has been removed so nothing the user didn't sign can load.
        val trustedFingerprints = mangaExtensionRepoRepository.getAll().map { it.signingKeyFingerprint }.toHashSet()
        return trustedFingerprints.any { fingerprints.contains(it) }
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions().getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions().delete()
    }
}
