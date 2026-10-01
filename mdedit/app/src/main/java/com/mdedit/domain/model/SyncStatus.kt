package com.mdedit.domain.model

enum class SyncStatus {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    SYNCING,
    SYNCED,
    ERROR
}
