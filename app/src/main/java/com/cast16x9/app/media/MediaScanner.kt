package com.cast16x9.app.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.cast16x9.app.model.MediaItem
import com.cast16x9.app.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaScanner(private val context: Context) {

    suspend fun loadVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Video $id"
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durCol)
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val path = cursor.getString(dataCol)
                    val uri = ContentUris.withAppendedId(collection, id)

                    list.add(MediaItem(id, title, uri, path, size, duration, MediaType.VIDEO, mime))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun loadPhotos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.TITLE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.TITLE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Photo $id"
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val path = cursor.getString(dataCol)
                    val uri = ContentUris.withAppendedId(collection, id)

                    list.add(MediaItem(id, title, uri, path, size, 0, MediaType.PHOTO, mime))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }
}
