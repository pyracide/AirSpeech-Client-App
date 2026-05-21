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
    private const val CACHE_DIR_NAME = "strokes"
    private const val MAX_FILES_LIMIT = 200

    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Keep track of files currently uploading to avoid duplicates
    private val uploadingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Queues a bitmap to be saved to the cache and then uploaded in the background.
     * This is non-blocking and safe to call from the UI thread.
     */
    fun queueUpload(context: Context, bitmap: Bitmap, filename: String) {
        val appContext = context.applicationContext
        
        // Copy the bitmap configuration/pixels on the main thread to ensure it remains unchanged
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapCopy = try {
            bitmap.copy(config, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bitmap", e)
            return
        }

        executor.execute {
            try {
                val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                // 1. Enforce safety cap (max 200 files)
                enforceCap(cacheDir)

                // 2. Save bitmap to cache
                val file = File(cacheDir, filename)
                FileOutputStream(file).use { out ->
                    bitmapCopy.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmapCopy.recycle() // free memory of copy

                Log.d(TAG, "Saved stroke to cache: ${file.absolutePath}")

                // 3. Trigger upload of all cached files
                triggerUploads(appContext)

            } catch (e: Exception) {
                Log.e(TAG, "Error saving/uploading stroke", e)
                try {
                    bitmapCopy.recycle()
                } catch (ex: Exception) {}
            }
        }
    }

    /**
     * Triggers upload of all cached files. Can be called on app startup too.
     */
    fun triggerUploads(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
                if (!cacheDir.exists() || !cacheDir.isDirectory) return@execute

                val files = cacheDir.listFiles() ?: return@execute
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

    private fun enforceCap(cacheDir: File) {
        val files = cacheDir.listFiles() ?: return
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
