package com.droidnova.allfilereader.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build

enum class IncomingUriSource { Data, ClipData, ExtraStream }

data class IncomingRequest(
    val uri: Uri,
    val declaredMimeType: String?,
    val readGrantFlags: Int,
    val source: IncomingUriSource
)

sealed interface IncomingExtraction {
    data class Ready(val request: IncomingRequest) : IncomingExtraction
    data object Missing : IncomingExtraction
    data object Ambiguous : IncomingExtraction
}

/** Extracts exactly one logical URI without accepting arbitrary parcelables or nested intents. */
object IncomingUriExtractor {
    fun extract(intent: Intent): IncomingExtraction = try {
        extractUnchecked(intent)
    } catch (_: RuntimeException) {
        IncomingExtraction.Ambiguous
    }

    private fun extractUnchecked(intent: Intent): IncomingExtraction {
        val data = intent.data
        val clip = intent.clipData
        val hasStream = intent.hasExtra(Intent.EXTRA_STREAM)
        val streamUri = if (hasStream) streamUri(intent) else null
        val clipUris = clip?.let { value -> (0 until value.itemCount).map { value.getItemAt(it).uri } }
        val selection = select(data?.toString(), clipUris?.map { it?.toString() }, hasStream, streamUri?.toString())
        val source = when (selection) {
            UriSelection.Missing -> return IncomingExtraction.Missing
            UriSelection.Ambiguous -> return IncomingExtraction.Ambiguous
            is UriSelection.Selected -> selection.source
        }
        val uri = when (source) {
            IncomingUriSource.Data -> data
            IncomingUriSource.ClipData -> clipUris?.singleOrNull()
            IncomingUriSource.ExtraStream -> streamUri
        } ?: return IncomingExtraction.Ambiguous
        return IncomingExtraction.Ready(
            IncomingRequest(
                uri = uri,
                declaredMimeType = intent.type,
                readGrantFlags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
                source = source
            )
        )
    }

    internal sealed interface UriSelection {
        data class Selected(val source: IncomingUriSource) : UriSelection
        data object Missing : UriSelection
        data object Ambiguous : UriSelection
    }

    internal fun select(data: String?, clipItems: List<String?>?, streamPresent: Boolean, stream: String?): UriSelection {
        if (clipItems != null && clipItems.size != 1) return UriSelection.Ambiguous
        val clip = clipItems?.singleOrNull()
        if (streamPresent && stream == null) return UriSelection.Ambiguous
        val supplied = listOfNotNull(data, clip, stream)
        if (supplied.isEmpty()) return UriSelection.Missing
        if (supplied.distinct().size != 1) return UriSelection.Ambiguous
        return UriSelection.Selected(when {
            data != null -> IncomingUriSource.Data
            clip != null -> IncomingUriSource.ClipData
            else -> IncomingUriSource.ExtraStream
        })
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}
