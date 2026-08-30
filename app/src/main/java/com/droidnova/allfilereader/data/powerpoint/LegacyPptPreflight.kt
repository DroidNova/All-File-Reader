package com.droidnova.allfilereader.data.powerpoint

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

sealed interface LegacyPptValidation {
    data object Valid : LegacyPptValidation
    data object Empty : LegacyPptValidation
    data object InvalidSignature : LegacyPptValidation
    data object Unreadable : LegacyPptValidation
}

/** A deliberately shallow, bounded check before offering a legacy file to another app. */
class LegacyPptPreflight(private val contentResolver: ContentResolver) {
    fun validate(uri: Uri): LegacyPptValidation = validate { contentResolver.openInputStream(uri) }

    companion object {
        private val OLE_SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )

        internal fun inspect(input: InputStream): LegacyPptValidation {
            val header = ByteArray(OLE_SIGNATURE.size)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) break
                if (read == 0) break
                count += read
            }
            if (count == 0) return LegacyPptValidation.Empty
            if (count != OLE_SIGNATURE.size || !header.contentEquals(OLE_SIGNATURE)) {
                return LegacyPptValidation.InvalidSignature
            }
            return LegacyPptValidation.Valid
        }

        internal fun validate(openStream: () -> InputStream?): LegacyPptValidation = try {
            openStream()?.use(::inspect) ?: LegacyPptValidation.Unreadable
        } catch (_: SecurityException) {
            LegacyPptValidation.Unreadable
        } catch (_: IOException) {
            LegacyPptValidation.Unreadable
        }
    }
}
