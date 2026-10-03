package tachiyomi.source.local.image.anime

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.anime.LocalAnimeSourceFileSystem
import java.io.InputStream

private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"

actual class LocalEpisodeThumbnailManager(
    private val context: Context,
    private val fileSystem: LocalAnimeSourceFileSystem,
) {

    actual fun find(animeUrl: String, fileName: String): UniFile? {
        return fileSystem.getFilesInAnimeDirectory(animeUrl)
            // fileName carries its extension ("<episode>-thumbnail.jpg"), so match the full name.
            // Comparing it against nameWithoutExtension never matched, which made update() create
            // a fresh file on every refresh instead of overwriting the existing thumbnail.
            .filter { it.isFile && it.name.equals(fileName, ignoreCase = true) }
            // Get the first actual image
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
    }

    actual fun update(anime: SAnime, episode: SEpisode, inputStream: InputStream): UniFile? {
        val directory = fileSystem.getAnimeDirectory(anime.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val fileName = "${episode.name}-$DEFAULT_THUMBNAIL_NAME"
        val targetFile = find(anime.url, fileName) ?: directory.createFile(fileName)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        episode.preview_url = targetFile.uri.toString()
        return targetFile
    }
}
