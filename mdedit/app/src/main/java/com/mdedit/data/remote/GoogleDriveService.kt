package com.mdedit.data.remote

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.mdedit.domain.model.DriveFileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: DriveAuthManager
) {
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = NetHttpTransport()

    private fun getDriveClient(): Drive? {
        val account: GoogleSignInAccount = authManager.signedInAccount.value ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            httpTransport,
            jsonFactory,
            credential
        ).setApplicationName("MDEdit").build()
    }

    suspend fun listFiles(): Result<List<DriveFileInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveClient() ?: throw IllegalStateException("User not signed in to Google Drive")
            
            // Query non-trashed markdown or text files accessible by the app
            val result = drive.files().list()
                .setQ("trashed = false and mimeType != 'application/vnd.google-apps.folder'")
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime, size)")
                .setOrderBy("modifiedTime desc")
                .setPageSize(50)
                .execute()

            result.files?.map { file ->
                DriveFileInfo(
                    id = file.id,
                    name = file.name ?: "Untitled.md",
                    modifiedTime = file.modifiedTime?.value ?: System.currentTimeMillis(),
                    size = file.getSize() ?: 0L
                )
            } ?: emptyList()
        }
    }

    suspend fun readFileContent(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveClient() ?: throw IllegalStateException("User not signed in to Google Drive")
            
            drive.files().get(fileId).executeMediaAsInputStream().use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        }
    }

    suspend fun createFile(title: String, content: String): Result<DriveFileInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveClient() ?: throw IllegalStateException("User not signed in to Google Drive")
            
            val fileName = if (title.endsWith(".md", ignoreCase = true)) title else "$title.md"
            val fileMetadata = File().apply {
                name = fileName
                mimeType = "text/markdown"
            }

            val mediaContent = ByteArrayContent("text/markdown", content.toByteArray(Charsets.UTF_8))
            val created = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, name, modifiedTime, size")
                .execute()

            DriveFileInfo(
                id = created.id,
                name = created.name,
                modifiedTime = created.modifiedTime?.value ?: System.currentTimeMillis(),
                size = created.getSize() ?: content.length.toLong()
            )
        }
    }

    suspend fun updateFile(fileId: String, title: String, content: String): Result<DriveFileInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveClient() ?: throw IllegalStateException("User not signed in to Google Drive")
            
            val fileName = if (title.endsWith(".md", ignoreCase = true)) title else "$title.md"
            val fileMetadata = File().apply {
                name = fileName
            }

            val mediaContent = ByteArrayContent("text/markdown", content.toByteArray(Charsets.UTF_8))
            val updated = drive.files().update(fileId, fileMetadata, mediaContent)
                .setFields("id, name, modifiedTime, size")
                .execute()

            DriveFileInfo(
                id = updated.id,
                name = updated.name,
                modifiedTime = updated.modifiedTime?.value ?: System.currentTimeMillis(),
                size = updated.getSize() ?: content.length.toLong()
            )
        }
    }

    suspend fun deleteFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveClient() ?: throw IllegalStateException("User not signed in to Google Drive")
            drive.files().delete(fileId).execute()
            Unit
        }
    }
}
