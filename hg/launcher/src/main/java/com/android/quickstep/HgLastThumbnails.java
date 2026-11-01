// HyperGestures: the last screenshot recents had of each task, whatever its windowing mode then.
//
// A task that leaves HyperOS's split screen comes back fullscreen: the thumbnail cache drops the
// entry it held for the task in split (TaskKey and the cache compare the windowing mode), and
// HyperOS has no snapshot at the requested resolution, so the card stayed empty (owner,
// 2026-10-09: grey and black cards for the apps that had been in split). TaskThumbnailCache shows
// the last screenshot taken in split instead when the system has none at any resolution: maybe a
// little out of date, never blank (not for other tasks: App lock and secure windows withhold
// snapshots on purpose). Only references to bitmaps the cache already held, and only to those
// taken in split; bounded like that cache.
package com.android.quickstep;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HgLastThumbnails<T> {
    private final LinkedHashMap<Integer, T> mByTaskId;

    /** Keeps at most {@code maxSize} tasks, least recently used out first. */
    public HgLastThumbnails(int maxSize) {
        int max = Math.max(1, maxSize);
        mByTaskId = new LinkedHashMap<>(16, 0.75f, true /* accessOrder */) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, T> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * A screenshot of {@code taskId} recents got; {@code null} (none) leaves the last one. Only one
     * worth falling back to ({@code fallback}: taken in split) is held: any other is the task's
     * latest screenshot and no fallback, so it just drops the one before, and lastOf answers null
     * as it would with this one held. No strong reference to every bitmap the cache sees.
     */
    public synchronized void remember(int taskId, @Nullable T screenshot, boolean fallback) {
        if (screenshot == null) return;
        if (fallback) {
            mByTaskId.put(taskId, screenshot);
        } else {
            mByTaskId.remove(taskId);
        }
    }

    /** The last screenshot of {@code taskId}, or null. */
    @Nullable
    public synchronized T lastOf(int taskId) {
        return mByTaskId.get(taskId);
    }

    /** The task is gone (its card removed). */
    public synchronized void forget(int taskId) {
        mByTaskId.remove(taskId);
    }

    /** Recents let go of its thumbnails (memory trim). */
    public synchronized void clear() {
        mByTaskId.clear();
    }
}
