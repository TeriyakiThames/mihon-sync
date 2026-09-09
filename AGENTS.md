# AGENTS.md — Mihon Sync Client

This document provides system architecture, design rules, and operational context for AI agents maintaining, debugging, or extending the `mihon-sync` Android client fork.

---

> [!IMPORTANT]
> **Architecture Decision Records (ADRs)**
> All major architectural decisions, trade-offs, and design rationales are formally recorded under [`docs/adr/`](docs/adr/).
> - Before making architectural or sync design modifications, agents **MUST** read the records in [`docs/adr/`](docs/adr/) (especially [ADR 0001](docs/adr/0001-sync-architecture-and-conflict-resolution.md)).
> - Whenever changing architectural design or introducing new conflict resolution patterns, agents **MUST** update the existing ADRs or write a new ADR in [`docs/adr/`](docs/adr/).

---

## 1. Codebase Organization

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

## 2. Database Timestamp Nuance (Critical)

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

## 3. Conflict Resolution & Change Tracking Strategy (ADR 0001)

`SyncMerger.kt` and SQLite triggers execute synchronization according to [ADR 0001](docs/adr/0001-sync-architecture-and-conflict-resolution.md):
1. **Outbox Dirty Tracking (`is_dirty`):**
   - Local database mutations set `is_dirty = 1` via SQLite update triggers whenever `new.is_syncing = 0 AND old.is_syncing = 0`.
   - Incoming remote updates merged during sync set `is_syncing = 1`, bypassing dirty flags.
   - Sync pushes only dirty rows and clears `is_dirty = 0` upon success. This decouples push extraction from remote timestamps, eliminating clock-skew bugs and preventing offline changes from being skipped.
2. **Last-Write-Wins (LWW) with Guaranteed Parity:**
   - Because the client automatically pulls on warm resume (`MainActivity.onResume()`), devices maintain library parity before reading.
   - Chapter reading progress (`last_page_read`), read status (`read`), bookmarks, and manga favorite status compare versions and modification timestamps. If the remote record is strictly newer, it wins (allowing users to unmark read and re-read).
   - History reading durations take `maxOf(remote, local)` by computing the delta `targetDuration - currentDuration` before calling SQLite `upsert`.
3. **Full Category Reconciliation:**
   - Category names, order (`sort`), and flags are updated to match remote state.
   - Manga-category memberships synchronize bidirectionally (reflecting additions and removals).
4. **Loop Suppression:**
   - Database writes during sync operations set `is_syncing = 1`.
   - SQLite update triggers require `WHEN new.is_syncing = 0 AND old.is_syncing = 0` to prevent sync merges from re-tagging rows as locally modified.

---

## 4. Lifecycle Triggers & Git-Style Synchronization

Synchronization follows a streamlined Git-style workflow:
1. **Warm Resume / App Launch (`git pull`)**: `MainActivity.onResume()` invokes:
   ```kotlin
   syncManager.triggerPull(skipIfRecentMs = 3000L)
   ```
   Pulls latest remote updates into SQLite so exact reading progress and active chapter are applied before entering the reader or library. If pending unpushed offline changes exist, they are queued for push.
2. **Leaving Reader (`git push`)**: `ReaderActivity.onPause()` flushes history and immediately invokes:
   ```kotlin
   syncManager.triggerPush(debounceDelayMs = 0L)
   ```
3. **Minimizing / Leaving App (`git push`)**: `MainActivity.onPause()` immediately invokes:
   ```kotlin
   syncManager.triggerPush(debounceDelayMs = 0L)
   ```
   Using `0ms` delay guarantees that changes are dispatched before Android can terminate the process if swiped away from Recents.

### Watermarks & Decoupling
- **Outbox-driven Push**: `pushToOrigin()` executes `internalPull()` first to reconcile remote state, then extracts and pushes all local dirty modifications (`is_dirty = 1`). Successfully pushed rows have their dirty flags reset.
- **Decoupled Watermarks**:
  - `lastPullTimestamp`: Tracks remote server stream position for `GET /api/sync?since=...`.
  - `lastSyncTimestamp`: Reflects the latest timestamp for UI display.
- **Reactive State (`isSyncing`)**: `SyncManager.isSyncing` (`StateFlow<Boolean>`) indicates active synchronization under `syncMutex`, driving live UI feedback (spinning toolbar refresh icon and indeterminate `LinearProgressIndicator` on `HistoryScreen`).
- **Battery & Data Friendly**: No network calls occur mid-reading (e.g. during chapter transitions); sync only occurs on exit/pause and initial app start / return from background.

---

## 5. Pairing URI Specification

The pairing URI encodes credentials for sharing between devices:
```
mihon-sync://pair?serverUrl=<url_encoded_server>&roomId=<room_id>&key=<base64_aes_key>
```
Handled by `SyncPairingUtil.createPairingUri()` and `SyncPairingUtil.parsePairingUri()`.
