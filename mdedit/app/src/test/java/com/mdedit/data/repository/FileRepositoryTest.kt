package com.mdedit.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

class FileRepositoryTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var fileRepository: FileRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        fileRepository = FileRepositoryImpl(context, testDispatcher)
    }

    @Test
    fun readFile_withValidUri_returnsSuccessWithContentAndFilename() = runTest(testDispatcher) {
        val sampleText = "# Test Document\n\nThis is a sample markdown file."
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://com.android.providers.media/documents/document/123"
        every { uri.lastPathSegment } returns "test.md"

        // Mock display name query
        val cursor = mockk<android.database.Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getString(0) } returns "CustomNotes.md"
        every {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        } returns cursor

        // Mock input stream
        val inputStream = ByteArrayInputStream(sampleText.toByteArray(Charsets.UTF_8))
        every { contentResolver.openInputStream(uri) } returns inputStream

        val result = fileRepository.readFile(uri)

        assertTrue("Expected read result to be success", result.isSuccess)
        val loaded = result.getOrNull()
        assertNotNull(loaded)
        assertEquals("CustomNotes.md", loaded?.filename)
        assertEquals(sampleText, loaded?.content)
        assertEquals(uri, loaded?.uri)
    }

    @Test
    fun readFile_withEmptyUri_returnsFailureIllegalArgumentException() = runTest(testDispatcher) {
        val emptyUri = mockk<Uri>()
        every { emptyUri.toString() } returns ""
        val result = fileRepository.readFile(emptyUri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun readFile_whenSecurityExceptionThrown_returnsFailureSecurityException() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://forbidden/file.md"
        every { uri.lastPathSegment } returns "file.md"

        every {
            contentResolver.query(uri, any(), any(), any(), any())
        } throws SecurityException("Permission denied for URI")

        every {
            contentResolver.openInputStream(uri)
        } throws SecurityException("Permission denied for URI")

        val result = fileRepository.readFile(uri)

        assertTrue("Expected failure on SecurityException", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun readFile_whenInputStreamNull_returnsFileNotFoundFailure() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://missing/file.md"
        every { uri.lastPathSegment } returns "file.md"
        every { contentResolver.openInputStream(uri) } returns null

        val result = fileRepository.readFile(uri)

        assertTrue("Expected failure when stream is null", result.isFailure)
        assertTrue(result.exceptionOrNull() is FileNotFoundException)
    }

    @Test
    fun readFile_withLargeFileContent_readsCompleteWithoutError() = runTest(testDispatcher) {
        // Generate >600KB text
        val largeBuilder = StringBuilder()
        for (i in 1..10000) {
            largeBuilder.append("Line $i: A quick brown fox jumps over the lazy dog. **Bold text** and `code block`.\n")
        }
        val largeContent = largeBuilder.toString()
        assertTrue("Content size must be > 500KB", largeContent.toByteArray().size > 500 * 1024)

        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://large/file.md"
        every { uri.lastPathSegment } returns "large_file.md"

        val inputStream = ByteArrayInputStream(largeContent.toByteArray(Charsets.UTF_8))
        every { contentResolver.openInputStream(uri) } returns inputStream

        val result = fileRepository.readFile(uri)

        assertTrue(result.isSuccess)
        assertEquals(largeContent.length, result.getOrNull()?.content?.length)
    }

    @Test
    fun writeFile_withValidUriAndContent_writesSuccessfully() = runTest(testDispatcher) {
        val content = "## Saved Content\n- item 1\n- item 2"
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://dest/file.md"

        val outputStream = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(uri, "wt") } returns outputStream

        val result = fileRepository.writeFile(uri, content)

        assertTrue(result.isSuccess)
        assertEquals(content, outputStream.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun writeFile_withEmptyUri_returnsFailure() = runTest(testDispatcher) {
        val emptyUri = mockk<Uri>()
        every { emptyUri.toString() } returns ""
        val result = fileRepository.writeFile(emptyUri, "data")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun writeFile_whenSecurityExceptionThrown_returnsFailure() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://locked/file.md"
        every { contentResolver.openOutputStream(uri, "wt") } throws SecurityException("Write permission revoked")

        val result = fileRepository.writeFile(uri, "data")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun getFileName_whenQueryFails_fallsBackToLastPathSegment() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://fallback/notes"
        every { uri.lastPathSegment } returns "my_notes"
        every { contentResolver.query(uri, any(), any(), any(), any()) } throws IOException("Query failed")

        val name = fileRepository.getFileName(uri)

        assertEquals("my_notes.md", name)
    }
}
