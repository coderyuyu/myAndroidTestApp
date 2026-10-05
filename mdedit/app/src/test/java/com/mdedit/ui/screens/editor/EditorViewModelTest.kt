package com.mdedit.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.mdedit.data.parser.MarkdownParser
import com.mdedit.data.remote.DriveAuthManager
import com.mdedit.data.repository.DocumentRepository
import com.mdedit.data.repository.FileLoadResult
import com.mdedit.data.repository.FileRepository
import com.mdedit.domain.model.Document
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var documentRepository: DocumentRepository
    private lateinit var fileRepository: FileRepository
    private lateinit var authManager: DriveAuthManager
    private lateinit var markdownParser: MarkdownParser
    private lateinit var savedStateHandle: SavedStateHandle
    private val testDispatcher = StandardTestDispatcher()

    private fun createViewModel(
        handle: SavedStateHandle = savedStateHandle
    ): EditorViewModel {
        return EditorViewModel(
            repository = documentRepository,
            fileRepository = fileRepository,
            authManager = authManager,
            markdownParser = markdownParser,
            savedStateHandle = handle
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        documentRepository = mockk(relaxed = true)
        fileRepository = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        markdownParser = MarkdownParser(testDispatcher)
        savedStateHandle = SavedStateHandle()

        every { documentRepository.getAllDocuments() } returns flowOf(emptyList())
        every { documentRepository.isOnline() } returns flowOf(true)
        every { documentRepository.isSyncing() } returns flowOf(false)
        every { authManager.signedInAccount } returns MutableStateFlow<GoogleSignInAccount?>(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_withoutSavedState_createsDefaultDocument() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Welcome to MDEdit", state.title)
        assertFalse(state.isDirty)
        assertNull(state.errorMessage)
    }

    @Test
    fun initialState_withSavedState_restoresUnsavedEditsAccurately() = runTest(testDispatcher) {
        // Simulate activity recreation or rotation retaining unsaved edits
        val handle = SavedStateHandle(
            mapOf(
                EditorViewModel.KEY_TITLE to "MeetingNotes.md",
                EditorViewModel.KEY_CONTENT to "# Unsaved Draft\n- action item",
                EditorViewModel.KEY_IS_DIRTY to true
            )
        )

        val viewModel = createViewModel(handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("MeetingNotes.md", state.title)
        assertEquals("# Unsaved Draft\n- action item", state.markdownContent)
        assertTrue("State must retain dirty flag across rotation", state.isDirty)
    }

    @Test
    fun onContentChanged_updatesState_andSetsDirtyFlag() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val updated = "## New Content"
        viewModel.onContentChanged(updated)

        assertEquals(updated, viewModel.uiState.value.markdownContent)
        assertTrue(viewModel.uiState.value.isDirty)
        assertEquals(updated, savedStateHandle.get<String>(EditorViewModel.KEY_CONTENT))
    }

    @Test
    fun onContentChanged_triggersDebouncedMarkdownParsing() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContentChanged("# Live Markdown Title")

        // Before 300ms debounce
        advanceTimeBy(100L)
        // After 300ms debounce
        advanceTimeBy(250L)
        advanceUntilIdle()

        val parsed = viewModel.uiState.value.parsedMarkdown
        assertTrue("Parsed markdown must contain rendered text", parsed.text.contains("Live Markdown Title"))
    }

    @Test
    fun onContentChanged_triggersDebouncedAutoSave() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        clearMocks(documentRepository, answers = false)

        viewModel.onContentChanged("Draft text to be autosaved")

        // AutoSave debounce is 2500ms
        advanceTimeBy(1000L)
        coVerify(exactly = 0) { documentRepository.saveDocumentLocally(any()) }

        advanceTimeBy(1600L)
        advanceUntilIdle()

        coVerify(atLeast = 1) { documentRepository.saveDocumentLocally(any()) }
        assertFalse("Dirty flag should be cleared after save", viewModel.uiState.value.isDirty)
    }

    @Test
    fun openFileFromUri_success_updatesContentAndClearsDirtyFlag() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://provider/doc.md"
        val sampleContent = "# External File\nImported from SAF."

        coEvery { fileRepository.readFile(uri) } returns Result.success(
            FileLoadResult(uri = uri, filename = "ExternalDoc.md", content = sampleContent)
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openFileFromUri(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("ExternalDoc.md", state.title)
        assertEquals(sampleContent, state.markdownContent)
        assertEquals(uri, state.currentFileUri)
        assertFalse(state.isDirty)
        assertNull(state.errorMessage)
    }

    @Test
    fun openFileFromUri_securityException_showsGracefulErrorMessageWithoutCrash() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://provider/forbidden.md"

        coEvery { fileRepository.readFile(uri) } returns Result.failure(
            SecurityException("Permission denied to URI")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.openFileFromUri(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull("Error message banner should be populated", state.errorMessage)
        assertTrue(state.errorMessage?.contains("Permission denied") == true)
        assertFalse("App must not crash on permission failure", state.isLoading)
    }

    @Test
    fun saveFileToUri_success_savesContentAndClearsDirtyFlag() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://provider/save_target.md"
        every { uri.lastPathSegment } returns "save_target.md"

        coEvery { fileRepository.writeFile(uri, any()) } returns Result.success(Unit)
        coEvery { fileRepository.getFileName(uri) } returns "save_target.md"

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onContentChanged("Content to save")
        assertTrue(viewModel.uiState.value.isDirty)

        viewModel.saveFileToUri(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Dirty flag should be false after save", state.isDirty)
        assertEquals(uri, state.currentFileUri)
        assertNull(state.errorMessage)
    }

    @Test
    fun saveFileToUri_failure_showsErrorMessage() = runTest(testDispatcher) {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://provider/dest.md"

        coEvery { fileRepository.writeFile(uri, any()) } returns Result.failure(
            SecurityException("Permission revoked")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.saveFileToUri(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("Permission denied") == true)
    }

    @Test
    fun togglePreviewMode_togglesFlagAndRefreshesPreview() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPreviewMode)

        viewModel.togglePreviewMode()
        assertTrue(viewModel.uiState.value.isPreviewMode)

        viewModel.togglePreviewMode()
        assertFalse(viewModel.uiState.value.isPreviewMode)
    }

    @Test
    fun clearErrorMessage_resetsErrorToNull() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://provider/err.md"
        coEvery { fileRepository.readFile(uri) } returns Result.failure(RuntimeException("Generic failure"))

        viewModel.openFileFromUri(uri)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        viewModel.clearErrorMessage()
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
