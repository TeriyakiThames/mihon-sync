# AGENTS.md — Mihon Sync Client

This document provides system architecture, design rules, and operational context for AI agents maintaining, debugging, or extending the `mihon-sync` Android client fork.

---

## 1. Prime Directive: Minimal Rebase Architecture

This repository is an active fork of upstream Mihon (`mihonapp/mihon`). Upstream Mihon frequently refactors its domain interactors and presentation layers.
To ensure this fork can rebase cleanly onto upstream releases with zero or minimal conflicts, adhere strictly to the following rules:

### Rules:
1. **DO NOT modify domain interactors:** Never inject sync callbacks or hooks directly into `tachiyomi/domain/*/interactor/*.kt` (e.g. `UpdateChapter`, `UpdateManga`, `UpsertHistory`).
2. **Keep sync code isolated:** All core synchronization logic, cryptographic utilities, networking clients, and serializers must live strictly inside `eu.kanade.tachiyomi.sync.*`.
3. **Use database diffing instead of event-sourcing:** When a sync event is triggered, query the existing SQLDelight database directly for rows modified after the last recorded sync watermark (`lastSyncTimestamp`).
4. **Target high-level lifecycle events:** Restrict activity-level modifications to simple one-liner calls to `SyncManager.triggerSync()` (e.g. in `onPause`).

---

## 2. Codebase Organization

```
mihon-sync/
├── app/src/main/java/eu/kanade/
│   ├── presentation/more/settings/screen/
│   │   ├── SettingsMainScreen.kt        # Added: "Sync" item in main settings
│   │   └── SettingsSyncScreen.kt        # Added: Jetpack Compose Sync Settings UI
│   ├── sync/                            # Added: All core sync logic
│   │   ├── crypto/
│   │   │   ├── CryptoUtil.kt            # AES-256-GCM encryption/decryption & key generator
│   │   │   └── SyncPairingUtil.kt       # mihon-sync:// URI encoder & parser
│   │   ├── data/
│   │   │   ├── SyncModels.kt            # Data transfer models (Manga, Chapter, History)
│   │   │   ├── SyncDiffEngine.kt        # Extracts database changes since timestamp
│   │   │   └── SyncMerger.kt            # Merges remote updates with LWW conflict resolution
│   │   └── service/
│   │       ├── SyncPreferences.kt       # PreferenceStore wrapper for sync state & credentials
│   │       ├── SyncApiClient.kt         # OkHttp client for /api/sync POST and GET
│   │       └── SyncManager.kt           # Push/pull orchestrator with Mutex & 1s debouncer
│   ├── ui/
│   │   ├── main/MainActivity.kt         # Modified: onPause() -> triggerSync()
│   │   └── reader/ReaderActivity.kt     # Modified: onPause() & chapter transition observer
├── data/src/main/sqldelight/tachiyomi/data/
│   └── sync.sq                          # Added: SQL queries for timestamp-based row extraction
├── i18n/src/commonMain/moko-resources/base/
│   └── strings.xml                      # Modified: Added sync localization strings
└── mihon/app/di/
    └── AppGraph.kt                      # Modified: Exposed syncPreferences and syncManager
```

---

## 3. Database Timestamp Nuance (Critical)

Pay close attention to timestamp resolution differences in SQLite:
- **`chapters.last_modified_at` and `mangas.last_modified_at`** are populated by SQLite triggers using `strftime('%s', 'now')` — they are stored in **SECONDS** since epoch.
- **`history.last_read` and sync network payloads** are stored in **MILLISECONDS** since epoch.

`SyncDiffEngine.kt` handles this translation:
```kotlin
val sinceSeconds = sinceTimestampMillis / 1000L
// Queries chapters and mangas with sinceSeconds
// Queries history with Date(sinceTimestampMillis)
```
Any future queries must respect this second vs. millisecond distinction.

---

## 4. Conflict Resolution Strategy

`SyncMerger.kt` executes incoming remote payloads using two complementary strategies:
1. **Monotonic Forward Reading Progress:**
   - Reading progress (`last_page_read`) and read status (`read`) only advance forward. A device that is behind never overwrites higher progress on another device.
2. **Last-Write-Wins (LWW):**
   - Manga favorite status, categories, and bookmarks compare local `last_modified_at` vs. remote update timestamps. The newer record wins.
3. **Loop Suppression:**
   - Database writes during sync operations set `is_syncing = 1` to prevent local SQLite update triggers from bumping `version` or re-tagging rows as locally modified.

---

## 5. Lifecycle Triggers & Debouncing

Synchronization is triggered on three events:
1. **Leaving Reader:** `ReaderActivity.onPause()`
2. **Leaving App:** `MainActivity.onPause()`
3. **Chapter Transition in Reader:** `ReaderActivity.kt` observing `viewModel.state.map { it.viewerChapters?.currChapter?.chapter?.id }`

All triggers invoke:
```kotlin
syncManager.triggerSync(debounceDelayMs = 1000L)
```
`SyncManager` debounces triggers by 1 second using Kotlin Coroutines, coalescing rapid events into a single background push/pull cycle.

---

## 6. Pairing URI Specification

The pairing URI encodes credentials for sharing between devices:
```
mihon-sync://pair?serverUrl=<url_encoded_server>&roomId=<room_id>&key=<base64_aes_key>
```
Handled by `SyncPairingUtil.createPairingUri()` and `SyncPairingUtil.parsePairingUri()`.
