package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extensionrepo.model.ExtensionRepo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
class BackupExtensionRepos(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String?,
    @ProtoNumber(4) var website: String,
    @ProtoNumber(5) var signingKeyFingerprint: String,
)

/**
 * Backups are untrusted input, so hold restored repos to the rule for adding one by hand: HTTPS only.
 */
fun BackupExtensionRepos.isValid(): Boolean {
    return baseUrl.toHttpUrlOrNull()?.isHttps == true && signingKeyFingerprint.isNotBlank()
}

val backupExtensionReposMapper = { repo: ExtensionRepo ->
    BackupExtensionRepos(
        baseUrl = repo.baseUrl,
        name = repo.name,
        shortName = repo.shortName,
        website = repo.website,
        signingKeyFingerprint = repo.signingKeyFingerprint,
    )
}
