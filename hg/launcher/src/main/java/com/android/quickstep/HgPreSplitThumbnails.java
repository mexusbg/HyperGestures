// HyperGestures: the last full-screen screenshot recents had of each task, for the option "Last
// full-screen preview after split" (HgRecentsSettings.PRE_SPLIT_PREVIEW; owner, 2026-11-04).
//
// An app in, or just out of, HyperOS's split screen has only a half-screen snapshot until it draws
// full screen again; its card shows the icon card (HgSplitPreview). With the option on, the card
// shows the screenshot recents had of the app before the split instead: out of date (it shows the
// app as it was before the split), but the app's own content; the icon card stays when there is
// none (the app never ran full screen since the launcher started, or it was in landscape).
//
// Only references to bitmaps the thumbnail cache already got: a snapshot's bitmap wraps the
// system's own buffer, so holding one costs memory only once the system has replaced it, which is
// the split case this is for. Bounded, least recently used out first; a task seen in split counts
// as used, so the cards recents loads meanwhile push out other tasks first.
package com.android.quickstep;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HgPreSplitThumbnails<T> {
    private final LinkedHashMap<Integer, T> mByTaskId;

    /** Keeps at most {@code maxSize} tasks, least recently used out first. */
    public HgPreSplitThumbnails(int maxSize) {
        int max = Math.max(1, maxSize);
        mByTaskId = new LinkedHashMap<>(16, 0.75f, true /* accessOrder */) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, T> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * A screenshot of {@code taskId} recents got; {@code null} (none) changes nothing. A
     * full-screen one ({@code fullScreen}, HgSplitPreview.fullScreenShape) replaces the held one;
     * any other keeps it and marks the task used.
     */
    public synchronized void remember(int taskId, @Nullable T screenshot, boolean fullScreen) {
        if (screenshot == null) return;
        if (fullScreen) {
            mByTaskId.put(taskId, screenshot);
        } else {
            mByTaskId.get(taskId); // access order: used
        }
    }

    /** The last full-screen screenshot of {@code taskId}, or null. */
    @Nullable
    public synchronized T lastOf(int taskId) {
        return mByTaskId.get(taskId);
    }

    /** The task is gone (its card removed). */
    public synchronized void forget(int taskId) {
        mByTaskId.remove(taskId);
    }

    /** Recents let go of its thumbnails (memory trim), or the option was turned off. */
    public synchronized void clear() {
        mByTaskId.clear();
    }
}
