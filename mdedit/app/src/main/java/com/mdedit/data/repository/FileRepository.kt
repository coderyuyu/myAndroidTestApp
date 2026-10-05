package com.mdedit.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result data model for a file loaded via Storage Access Framework (SAF).
 */
data class FileLoadResult(
    val uri: Uri,
    val filename: String,
    val content: String
)

/**
 * Repository interface for Storage Access Framework (SAF) document read/write and URI handling.
 */
interface FileRepository {
    /**
     * Reads a document from the given [Uri] on an IO dispatcher.
     * Takes persistable URI permission where applicable and parses content safely.
     */
    suspend fun readFile(uri: Uri): Result<FileLoadResult>

    /**
     * Writes [content] to the given [Uri] on an IO dispatcher with safe stream scoping.
     */
    suspend fun writeFile(uri: Uri, content: String): Result<Unit>

    /**
     * Resolves the human-readable display name for the given [Uri].
     */
    suspend fun getFileName(uri: Uri): String
}

/**
 * Production implementation of [FileRepository] interacting with [ContentResolver].
 */
@Singleton
class FileRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher
) : FileRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Dispatchers.IO)

    private val contentResolver: ContentResolver
        get() = context.contentResolver

    override suspend fun readFile(uri: Uri): Result<FileLoadResult> = withContext(ioDispatcher) {
        val uriStr = try { uri.toString() } catch (_: Throwable) { "" }
        if (uriStr.isNullOrBlank() || (Uri.EMPTY != null && uri == Uri.EMPTY)) {
            return@withContext Result.failure(IllegalArgumentException("File URI cannot be null or empty"))
        }

        // Attempt to persist URI permissions if supported by the provider
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not support persistable permissions or permission was already set
        } catch (_: Throwable) {
            // Non-fatal permission attempt
        }

        try {
            val filename = getFileNameInternal(uri)
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(
                    FileNotFoundException("Unable to open input stream for URI: $uri")
                )

            val content = inputStream.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }

            Result.success(FileLoadResult(uri = uri, filename = filename, content = content))
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Permission denied to read file: ${e.localizedMessage}", e))
        } catch (e: FileNotFoundException) {
            Result.failure(FileNotFoundException("File not found: ${e.localizedMessage}"))
        } catch (e: IOException) {
            Result.failure(IOException("I/O error reading file: ${e.localizedMessage}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun writeFile(uri: Uri, content: String): Result<Unit> = withContext(ioDispatcher) {
        val uriStr = try { uri.toString() } catch (_: Throwable) { "" }
        if (uriStr.isNullOrBlank() || (Uri.EMPTY != null && uri == Uri.EMPTY)) {
            return@withContext Result.failure(IllegalArgumentException("File URI cannot be null or empty"))
        }

        try {
            val outputStream = contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext Result.failure(
                    FileNotFoundException("Unable to open output stream for URI: $uri")
                )

            outputStream.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }

            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Permission denied to write to file: ${e.localizedMessage}", e))
        } catch (e: FileNotFoundException) {
            Result.failure(FileNotFoundException("File not found or destination inaccessible: ${e.localizedMessage}"))
        } catch (e: IOException) {
            Result.failure(IOException("I/O error writing file: ${e.localizedMessage}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFileName(uri: Uri): String = withContext(ioDispatcher) {
        getFileNameInternal(uri)
    }

    private fun getFileNameInternal(uri: Uri): String {
        if (uri == Uri.EMPTY || uri.toString().isBlank()) return "Untitled.md"

        var name: String? = null
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        } catch (_: Throwable) {
            // ContentResolver query failed; fallback to last path segment
        }

        if (name.isNullOrBlank()) {
            val lastSegment = uri.lastPathSegment
            name = if (!lastSegment.isNullOrBlank()) {
                val clean = lastSegment.substringAfterLast('/')
                if (!clean.endsWith(".md", ignoreCase = true) && !clean.contains(".")) {
                    "$clean.md"
                } else {
                    clean
                }
            } else {
                "Untitled.md"
            }
        }

        return name ?: "Untitled.md"
    }
}
