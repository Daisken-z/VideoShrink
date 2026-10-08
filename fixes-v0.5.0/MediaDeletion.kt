package com.example.videoshrink

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

object MediaDeletion {
    /**
     * Converts supported local picker/document URIs to a real MediaStore item URI.
     * Photo Picker returns read-only content://media/picker/... URIs. Those MUST NOT be passed
     * directly to MediaStore.createDeleteRequest(), even though their authority is "media".
     */
    fun toDeletableMediaStoreUri(context: Context, uri: Uri): Uri? {
        if (uri.scheme != "content") return null

        if (uri.authority == MediaStore.AUTHORITY) {
            if (isPhotoPickerUri(uri)) {
                return pickerUriToLocalMediaStoreUri(context, uri)
            }
            if (isSpecificMediaStoreItemUri(uri)) return uri
            return null
        }

        if (DocumentsContract.isDocumentUri(context, uri) &&
            uri.authority == "com.android.providers.media.documents"
        ) {
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val parts = documentId.split(':', limit = 2)
            if (parts.size != 2 || parts[0] != "video") return null
            val id = parts[1].toLongOrNull() ?: return null
            return videoUriForId(id)
        }
        return null
    }

    private fun isPhotoPickerUri(uri: Uri): Boolean {
        val first = uri.pathSegments.firstOrNull()
        return first == "picker" || first == "picker_get_content" || first == "picker_transcoded"
    }

    private fun isSpecificMediaStoreItemUri(uri: Uri): Boolean {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return false
        if (segments.firstOrNull() in setOf("picker", "picker_get_content", "picker_internal")) return false
        return segments.lastOrNull()?.toLongOrNull() != null
    }

    private fun pickerUriToLocalMediaStoreUri(context: Context, uri: Uri): Uri? {
        // Current Android picker format is typically:
        // content://media/picker/<userId>/<providerAuthority>/media/<mediaId>
        val segments = uri.pathSegments
        if (segments.size < 5) return null
        val providerAuthority = segments.getOrNull(2) ?: return null
        if (segments.getOrNull(3) != "media") return null
        val id = segments.lastOrNull()?.toLongOrNull() ?: return null

        // Never guess a MediaStore row for a cloud-only provider.
        if (Build.VERSION.SDK_INT >= 33) {
            val isCloud = runCatching {
                MediaStore.isCurrentCloudMediaProviderAuthority(context.contentResolver, providerAuthority)
            }.getOrDefault(false)
            if (isCloud) return null
        }

        return videoUriForId(id)
    }

    private fun videoUriForId(id: Long): Uri {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, id)
    }
}
