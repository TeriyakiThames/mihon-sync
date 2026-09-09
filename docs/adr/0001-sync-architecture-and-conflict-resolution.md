# ADR 0001: Sync Architecture, Outbox Dirty Tracking, and Lifecycle Synchronization

- **Status**: Accepted
- **Date**: 2026-09-09
- **Deciders**: Mihon Sync Team
- **Consulted**: Antigravity AI Agent

---

## 1. Context and Problem Statement

The `mihon-sync` client is an Android client fork that synchronizes manga libraries, reading progress, history, and categories end-to-end (E2EE) with an encrypted backend relay server (`POST /api/sync` and `GET /api/sync`).

Earlier implementations relied on timestamp-based diffing (`last_modified_at` in seconds and `history.last_read` in milliseconds) coupled with advancing `lastPushTimestamp` to `maxObservedTimestamp` during `internalPull()`. This introduced critical edge cases:

1. **Offline Modification Data Loss**:
   If Device B modified reading progress or favorites while offline (e.g. at 10:02) and subsequently reconnected at 10:05, pulling remote updates from Device A (timestamped 10:04) jumped Device B's push watermark (`lastPushTimestamp`) to 10:04. When Device B subsequently extracted local changes with `sinceTimestampMillis = 10:04`, its 10:02 local modifications were permanently skipped and never pushed.
2. **Clock Skew Vulnerability**:
   If Device A's system clock was ahead of Device B's clock, Device B's push watermark was advanced into the future, preventing any of Device B's local modifications from syncing until its local clock caught up.
3. **Cold-Start Only Pulling**:
   The app only pulled on initial process cold-start (`hasRunInitialPull`), meaning returning to the app while it remained cached in Android memory never fetched updates before reading started.
4. **Monotonic Forward Read Locking**:
   `SyncMerger` enforced strict monotonic forward progress (`localChapter.read -> true` and `maxOf(lastPageRead)`). Users could never unmark chapters as read or re-read chapters across devices.
5. **Add-Only Category Drift**:
   Categories and manga-category associations were strictly additive; deletions, reordering, and removals of manga from categories never synchronized.
6. **Recent Apps Swipe Drop**:
   `MainActivity.onPause()` had a 1000ms debounce delay; swiping the app away from Android Recents killed the process before the sync push was dispatched.

---

## 2. Decision Drivers

- **Zero Data Loss**: Offline changes must never be dropped, regardless of when remote updates were pulled or peer clock differences.
- **Echo Suppression**: Pulled remote updates must never be echoed back to the server.
- **Reading Parity**: Devices must pull latest state upon opening or returning from background before reading starts.
- **Flexibility**: Users must be able to mark chapters unread or re-read across devices.
- **Complete Organization Sync**: Categories, their sort order, and manga category memberships must mirror accurately.

---

## 3. Considered Options

1. **Option A: Outbox / Dirty Tracking (`is_dirty` flags)** [SELECTED]
   - Add `is_dirty INTEGER NOT NULL DEFAULT 0` to SQLite tables (`mangas`, `chapters`, `history`).
   - SQLite update/version triggers set `is_dirty = 1` only when `new.is_syncing = 0 AND old.is_syncing = 0`.
   - Incoming remote updates merged during sync set `is_syncing = 1`, bypassing `is_dirty`.
   - Sync extracts only rows marked `is_dirty = 1`, pushing them and clearing `is_dirty = 0` on success.
2. **Option B: Pure Local Monotonic Timestamps**
   - Retain timestamp diffing but never advance push watermark to remote timestamps.
   - Vulnerable to remote records touching `last_modified_at` or `history.last_read` during merge and causing echo loops.

---

## 4. Decision Outcome

We selected **Option A (Outbox Dirty Tracking)** alongside refined lifecycle and conflict resolution policies:

### 4.1 Outbox Dirty Tracking & Echo Elimination
- SQLite tables (`mangas`, `chapters`, `history`) track unpushed local changes via `is_dirty = 1`.
- Triggers activate only on user-initiated local modifications (`is_syncing = 0`).
- Sync merges set `is_syncing = 1`, ensuring pulled items are never tagged dirty.
- Diff extraction queries dirty rows directly (`is_dirty = 1`). On push confirmation, dirty flags for the pushed batch are cleared (`is_dirty = 0`).
- Local push watermarks are decoupled from remote server timestamps.

### 4.2 Lifecycle Triggers & Parity
- **Warm Resume Pull**: `MainActivity.onResume()` invokes `triggerPull(skipIfRecentMs = 3000L)` so returning to the app from the background fetches updates and ensures library parity before user interaction.
- **Immediate Pause Push**: `MainActivity.onPause()` and `ReaderActivity.onPause()` dispatch with `debounceDelayMs = 0L` to ensure pushes are sent immediately before the Android OS can kill the process from Recents.

### 4.3 Conflict Resolution: Last-Write-Wins (LWW)
- Because warm resume ensures parity before reading, chapter progress uses Last-Write-Wins based on chapter `version` and `lastModifiedAt`.
- When `isRemoteNewer` is true, remote `read` and `lastPageRead` overwrite local values, enabling unmarking read and chapter re-reading.

### 4.4 Full Category Reconciliation
- Category definitions (name, order, flags) update locally if changed remotely.
- Manga-category associations reconcile bidirectionally, reflecting category additions and removals.

---

## 5. Consequences

### Positive
- **Guaranteed Consistency**: Offline modifications are always pushed, regardless of peer timestamps or network delay.
- **Clock-Skew Immune**: Local push tracking does not depend on remote timestamps matching the local device clock.
- **No Echo Loops**: Server receives only genuinely local modifications.
- **Improved UX**: Users can unmark read, re-read chapters, and trust category organization across all devices.

### Negative / Maintenance
- Schema migration required to add `is_dirty` columns to existing SQLite tables.
- Query implementations in `sync.sq` must maintain dirty clearing within transactional boundaries.
