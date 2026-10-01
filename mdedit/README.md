# MDEdit - Android WYSIWYG Markdown Editor with Google Drive Sync

A modern Android application built with **Kotlin** and **Jetpack Compose** that delivers a true "What You See Is What You Get" (Typora/Notion-like) inline Markdown editing experience, paired with **Google Drive REST API v3** cloud synchronization and offline caching with **Room Database**.

---

## ✨ Features

- **True WYSIWYG Markdown Editing**:
  - Headings (H1, H2, H3) render in real-time with typographic sizing and accents.
  - Inline formatting (Bold, Italic, Strikethrough, Code) renders styled text directly in the editor.
  - Blockquotes with colored left border bars.
  - Monospace code blocks with shaded background container.
  - Interactive checklists (`- [ ]`, `- [x]`) and bullet/numbered lists.
  - Formatting toolbar with active-tag highlighting according to cursor selection.
  - **Dual Mode**: Toggle between Visual WYSIWYG Mode and Raw Markdown Mode anytime.
- **Strict Markdown Fidelity**:
  - Documents remain standard `.md` files under the hood.
  - Bidirectional lossless conversion between rich visual DOM and Markdown text.
- **Google Drive Integration**:
  - Authenticates via Google Sign-In with minimal scope: `https://www.googleapis.com/auth/drive.file`.
  - Lists and imports existing `.md` files created by this app on Google Drive.
  - Auto-creates and updates files in Drive.
- **Offline Mode & Auto-Save**:
  - Debounced auto-save (2.5 seconds inactivity) saves changes locally to Room database.
  - Network observer monitors connectivity: when internet is restored, pending changes are automatically uploaded to Google Drive.
  - Visual status indicator showing Synced (green), Syncing (blue), Pending/Offline (amber), or Local-only.
- **Modern Architecture**:
  - Clean Architecture + MVVM.
  - Dependency Injection with Dagger Hilt.
  - Asynchronous workflows with Kotlin Coroutines & Flow.
  - Jetpack Compose Material 3 with Dynamic Color and Dark/Light theme support.

---

## 🏛️ Project Architecture

```
com.mdedit
├── data
│   ├── local
│   │   ├── AppDatabase.kt          # Room database definition
│   │   ├── dao/DocumentDao.kt      # DAO for CRUD and pending sync queries
│   │   └── entity/DocumentEntity.kt # Room database entity with converters
│   ├── remote
│   │   ├── DriveAuthManager.kt     # Google Sign-In & account state
│   │   ├── GoogleDriveService.kt   # Drive API v3 operations (CRUD)
│   │   └── DriveSyncManager.kt     # Network listener & online/offline sync
│   └── repository
│       ├── DocumentRepository.kt   # Repository interface
│       └── DocumentRepositoryImpl.kt
├── domain
│   └── model
│       ├── Document.kt             # Domain document model
│       ├── DriveFileInfo.kt        # Drive file metadata model
│       ├── EditorFormatState.kt    # Active formatting tags state
│       └── SyncStatus.kt           # Sync status enum
├── di
│   ├── DatabaseModule.kt           # Provides Room & DAO
│   └── DriveModule.kt              # Binds repository implementations
├── ui
│   ├── components
│   │   ├── DocumentListDrawer.kt   # Drawer with history, auth, and actions
│   │   ├── DriveFileListDialog.kt  # Drive file explorer dialog
│   │   ├── EditorToolbar.kt        # Formatting toolbar with active states
│   │   ├── SyncIndicator.kt        # Animated sync status badge
│   │   └── WysiwygEditorView.kt    # Compose wrapper for the rich editor engine
│   ├── screens/editor
│   │   ├── EditorScreen.kt         # Main Compose UI
│   │   ├── EditorUiState.kt        # Screen UI state
│   │   └── EditorViewModel.kt      # ViewModel with debounced auto-save
│   └── theme
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
├── MainActivity.kt                 # Single Activity entry point
└── MdEditApplication.kt            # Hilt Application class
```

---

## 🔑 Google Cloud Console Configuration (OAuth 2.0)

To connect the application to Google Drive:

1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (e.g., `MDEdit-App`).
3. Enable the **Google Drive API**:
   - Navigate to **APIs & Services > Library**.
   - Search for **Google Drive API** and click **Enable**.
4. Configure the **OAuth Consent Screen**:
   - Choose **External** (or **Internal** if using Google Workspace).
   - Add App Name and support emails.
   - Under **Scopes**, add: `https://www.googleapis.com/auth/drive.file`.
   - Add test users under **Test users**.
5. Create **OAuth 2.0 Client ID**:
   - Go to **APIs & Services > Credentials > Create Credentials > OAuth client ID**.
   - Select **Android** as Application Type.
   - Package name: `com.mdedit`
   - SHA-1 certificate fingerprint:
     Run in terminal to get your debug keystore SHA-1:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
6. Deploy and run the app. Tap **Connect Google Drive** in the navigation menu to authenticate.

---

## 🛠️ Building & Running

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Assemble Debug APK
```bash
./gradlew assembleDebug
```
The generated APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`
