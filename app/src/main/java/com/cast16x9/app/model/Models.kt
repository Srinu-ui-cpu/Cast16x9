package com.cast16x9.app.model

import android.net.Uri

enum class MediaType {
    VIDEO,
    PHOTO,
    AUDIO,
    FILE
}

data class MediaItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val path: String?,
    val size: Long,
    val duration: Long = 0,
    val type: MediaType,
    val mimeType: String
)

data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val uri: Uri,
    val mimeType: String,
    val isVideo: Boolean = false,
    val isPhoto: Boolean = false
)
