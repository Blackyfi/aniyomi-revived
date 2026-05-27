package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    /**
     * Decode a potentially-gzipped backup.
     */
    fun decode(uri: Uri): Backup {
        return context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            val source = inputStream.source().buffer()

            val peeked = source.peek().apply {
                require(2)
            }
            val id1id2 = peeked.readShort()
            val backupString = when (id1id2.toInt()) {
                0x1f8b -> source.gzip().buffer() // 0x1f8b is gzip magic bytes
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                }
                else -> source
            }.use { it.readByteArrayCapped() }

            try {
                if (BackupDetector.isLegacyBackup(backupString)) {
                    parser.decodeFromByteArray(LegacyBackup.serializer(), backupString)
                        .toBackup()
                } else {
                    parser.decodeFromByteArray(Backup.serializer(), backupString)
                }
            } catch (_: SerializationException) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
    }

    /**
     * Reads the (decompressed) stream fully, aborting if it exceeds [MAX_BACKUP_SIZE]. This
     * prevents a small crafted gzip "decompression bomb" from expanding into a multi-gigabyte
     * array and crashing the app with an OutOfMemoryError on import.
     */
    private fun BufferedSource.readByteArrayCapped(): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = read(buffer, READ_CHUNK)
            if (read == -1L) break
            total += read
            if (total > MAX_BACKUP_SIZE) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
        return buffer.readByteArray()
    }

    companion object {
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`

        // Generous upper bound for a decompressed backup (metadata only, no images).
        private const val MAX_BACKUP_SIZE = 200L * 1024 * 1024
        private const val READ_CHUNK = 64L * 1024
    }
}
