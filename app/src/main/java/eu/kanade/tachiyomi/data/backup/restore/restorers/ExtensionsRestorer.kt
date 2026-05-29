package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.util.storage.getUriCompat
import uy.kohesive.injekt.api.get
import java.io.File

class ExtensionsRestorer(
    private val context: Context,
) {

    fun restoreExtensions(extensions: List<BackupExtension>) {
        extensions.forEach {
            // pkgName comes straight from the (untrusted) backup and is used as a filename
            // below. Reject anything that isn't a valid Android package name to prevent path
            // traversal / arbitrary file writes via crafted backups.
            if (!it.pkgName.matches(PACKAGE_NAME_REGEX)) {
                return@forEach
            }
            if (context.packageManager.getInstalledPackages(0).none { pkg -> pkg.packageName == it.pkgName }) {
                // save apk in files dir and open installer dialog
                val file = File(context.cacheDir, "${it.pkgName}.apk")
                // Defense in depth: ensure the resolved path stays within cacheDir.
                if (!file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)) {
                    return@forEach
                }
                file.writeBytes(it.apk)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(
                        file.getUriCompat(context),
                        "application/vnd.android.package-archive",
                    )
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                context.startActivity(intent)
            }
        }
    }

    companion object {
        // A valid Android package name: dot-separated identifiers, each starting with a letter.
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
