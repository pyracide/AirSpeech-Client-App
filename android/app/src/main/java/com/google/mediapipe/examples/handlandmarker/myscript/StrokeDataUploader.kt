package com.google.mediapipe.examples.handlandmarker.myscript

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object StrokeDataUploader {
    private const val TAG = "StrokeDataUploader"
    private const val API_KEY = "airwritingbysamgray646thegoat!"
    private const val UPLOAD_URL = "https://foruiszijstbsroimtbe.supabase.co/functions/v1/upload-stroke"
    private const val STROKES_DIR_NAME = "strokes"
    private const val MAX_FILES_LIMIT = 200

    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Keep track of files currently uploading to avoid duplicates
    private val uploadingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Saves a bitmap synchronously to permanent storage and then queues its upload in the background.
     * Safe to call from the UI thread because the bitmap is small and saving takes minimal time.
     */
    fun queueUpload(context: Context, bitmap: Bitmap, filename: String) {
        val appContext = context.applicationContext
        
        val storageDir = File(appContext.filesDir, STROKES_DIR_NAME)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        // 1. Enforce safety cap (max 200 files)
        enforceCap(storageDir)

        // 2. Save bitmap to permanent storage synchronously
        val file = File(storageDir, filename)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved stroke synchronously to permanent storage: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stroke synchronously", e)
        }

        // 3. Trigger upload of all pending files asynchronously
        executor.execute {
            triggerUploads(appContext)
        }
    }

    /**
     * Triggers upload of all pending files in permanent storage. Can be called on app startup too.
     */
    fun triggerUploads(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val storageDir = File(appContext.filesDir, STROKES_DIR_NAME)
                if (!storageDir.exists() || !storageDir.isDirectory) return@execute

                val files = storageDir.listFiles() ?: return@execute
                // Sort by lastModified so we process in order
                files.sortBy { it.lastModified() }

                for (file in files) {
                    if (file.isFile && file.extension.lowercase() == "png") {
                        uploadFile(file)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in triggerUploads", e)
            }
        }
    }

    private fun enforceCap(storageDir: File) {
        val files = storageDir.listFiles() ?: return
        if (files.size >= MAX_FILES_LIMIT) {
            // Sort by last modified to find oldest
            files.sortBy { it.lastModified() }
            // Delete oldest files to make room (need files.size - MAX_FILES_LIMIT + 1 spaces)
            val numToDelete = files.size - MAX_FILES_LIMIT + 1
            for (i in 0 until numToDelete) {
                if (i < files.size) {
                    val fileToDelete = files[i]
                    if (fileToDelete.delete()) {
                        Log.d(TAG, "Cap exceeded. Deleted oldest cached file: ${fileToDelete.name}")
                    }
                }
            }
        }
    }

    private fun uploadFile(file: File) {
        val fileName = file.name
        if (!uploadingFiles.add(fileName)) {
            // Already uploading this file
            return
        }

        try {
            Log.d(TAG, "Starting upload for: $fileName")
            val mediaType = "image/png".toMediaTypeOrNull()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, file.asRequestBody(mediaType))
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .header("X-API-Key", API_KEY)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload successful for: $fileName. Server code: ${response.code}")
                    // Delete from disk upon success
                    if (file.delete()) {
                        Log.d(TAG, "Deleted file from cache: $fileName")
                    }
                } else {
                    Log.e(TAG, "Upload failed for: $fileName. Code: ${response.code}, Message: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network or system error uploading: $fileName", e)
        } finally {
            uploadingFiles.remove(fileName)
        }
    }
}
