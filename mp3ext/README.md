# MP3 智慧長度延長 (MP3 Extender) - Android App

原生 Android 音訊智慧無縫延長與編輯應用程式，基於 **Kotlin**、**Jetpack Compose (Material 3)**、**Media3 (ExoPlayer)** 與 **Android 原生 MediaCodec / MediaExtractor** 開發。

---

## 🌟 核心功能亮點

1. **本機音訊選取與智慧解析**：
   - 透過 Android 原生 SAF (`ActivityResultContracts.GetContent`) 安全選取音訊檔案。
   - 解析檔名、取樣頻率 (Sample Rate)、聲道數 (Channels)、原始長度 (`mm:ss`)、檔案大小與 MIME 格式。

2. **自訂目標延長時長**：
   - 直覺的時間滑桿 (Slider) 與數字格式化 (`mm:ss`)。
   - 快速操作晶片按鈕：`+30 秒`、`+1 分鐘`、`2x (雙倍原長)`、`3x (三倍原長)`。
   - 支援最高 30 分鐘以上的平滑延長設定。

3. **智慧無縫銜接與長度控制演算法 (誤差嚴格 ≤ 5%)**：
   - **結構切分與接點分析**：
     - 自動保留開頭 Intro 與結尾 Outro，保護樂曲起伏與結尾完整度。
     - 在中間主體區間尋找 RMS 能量相近、正斜率過零點（Zero-Crossing）與波形互相關最佳吻合點，避免相位抵消或斷音喀嗒聲 (Clicks)。
   - **動態長度配比與誤差檢核**：
     - 目標區間定義：`TargetMin = TargetLength * 0.95`，`TargetMax = TargetLength * 1.05`。
     - 計算循環體重複次數 $N$ 與 Crossfade 疊合時長（500ms ~ 1500ms 等功率曲線）。
     - **微調策略**：若固定段落循環略微超出範圍，自動微調循環片段長度（Loop Window）與 Crossfade 重疊時長。
     - **最終微裁切/淡出**：若因樂句完整性導致略長，將結尾 Outro 在接回時略微提早平滑淡出，強制確保總時長嚴格落在 `[TargetMin, TargetMax]` 區間內。

4. **雙軌對比播放器 (Media3 ExoPlayer)**：
   - 分頁切換試聽：「原始音訊」 vs 「延長後音訊」。
   - 提供播放 / 暫停、拖曳進度條 (SeekBar)、當前時間與總時長顯示。

5. **音訊匯出與相容性說明**：
   - **AAC / M4A (原生推薦)**：透過 Android 原生硬體加速 `MediaCodec` 編碼為 AAC (`audio/mp4a-latm`) 並以 `MediaMuxer` 封裝為標準 `.m4a` 檔案。100% Android 設備皆原生支援，音質佳、檔案小，各大剪輯軟體（PowerDirector、CapCut、Premiere）與播放器通用。
   - **WAV (無損 PCM)**：直接寫入 44-byte RIFF 標頭與未壓縮 16-bit PCM，適用於音訊專業軟體。
   - 支援 SAF `ActivityResultContracts.CreateDocument` 匯出至手機「下載」或「音樂」目錄。

---

## 🛠 系統架構

```
com.mp3ext/
├── MainActivity.kt                # 程式進入點、Android 13+ 權限請求 (READ_MEDIA_AUDIO)
├── audio/
│   ├── AudioModels.kt             # 音訊詮釋資料、配置參數與處理狀態類別
│   ├── AudioDecoder.kt            # MediaExtractor + MediaCodec 將 MP3 解碼為 16-bit PCM
│   ├── AudioExtender.kt           # 智慧波形分析、過零點偵測與等功率 Crossfade 循環拼接
│   ├── AudioEncoder.kt            # MediaCodec AAC/M4A 硬體編碼與 WAV 無損封裝
│   ├── AudioProcessor.kt          # 協調整合 Decode -> Extend -> Encode 之背景管線
│   └── AudioPlayerManager.kt      # Media3 ExoPlayer 雙音訊軌道播放管理
└── ui/
    ├── MainViewModel.kt           # StateFlow 狀態管理與 Coroutine 非同步排程
    ├── MainScreen.kt              # Material 3 現代暗黑風格 Compose UI
    └── theme/                     # 沉浸式科技藍/青/紫配色主題
```

---

## 🚀 建置與測試

- **Gradle 建置**：
  ```bash
  ./gradlew.bat assembleDebug
  ```
  生成之 APK 檔案路徑：
  `app/build/outputs/apk/debug/app-debug.apk`

- **執行單元測試**：
  ```bash
  ./gradlew.bat test
  ```
  包含合成 44.1 kHz 正弦波、音訊延長演算法時長精確度測試及格式化驗證。
