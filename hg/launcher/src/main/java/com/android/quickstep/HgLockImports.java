// HyperGestures: when HgTaskLockStore imports a user's HyperOS locked list, extracted so it is
// testable (no Android types).
//
// The import is a binder call (HgMiuiProcessLocks.getLocked). Card binds only queue it on a
// background executor; Clear all and Lock/Unlock run it on the main thread and must not return
// before an import that is already in flight has finished, or Clear all could remove an app the
// user locked in stock HyperOS. Every attempt runs under one import lock, so a synchronous import
// waits for a background one and then finds the user imported. A failed attempt (the API is
// missing, or the call threw) is retried up to MAX_ATTEMPTS times per process, then given up.
package com.android.quickstep;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

final class HgLockImports {
    /** Reads a user's HyperOS locked packages; null if that failed. */
    interface Fetcher {
        List<String> fetch(int userId);
    }

    /** Takes a fetched list (and records the user as imported). */
    interface Sink {
        void merge(int userId, List<String> packages);
    }

    /** Whether a user's list was already imported (persisted by the sink). */
    interface ImportedCheck {
        boolean isImported(int userId);
    }

    static final int MAX_ATTEMPTS = 3;

    private final Fetcher mFetcher;
    private final Sink mSink;
    private final ImportedCheck mImported;
    private final Executor mBackground;
    /** Held for a whole attempt, binder call included. */
    private final Object mImportLock = new Object();
    // Guarded by this.
    private final Set<Integer> mQueued = new HashSet<>();
    private final Map<Integer, Integer> mFailures = new HashMap<>();

    HgLockImports(Fetcher fetcher, Sink sink, ImportedCheck imported, Executor background) {
        mFetcher = fetcher;
        mSink = sink;
        mImported = imported;
        mBackground = background;
    }

    /** Imports {@code userId}'s list on this thread if due, waiting for one in flight. */
    void importNow(int userId) {
        if (!isDue(userId)) return;
        synchronized (mImportLock) {
            if (!isDue(userId)) return;
            List<String> packages = mFetcher.fetch(userId);
            if (packages != null) {
                mSink.merge(userId, packages);
            } else {
                synchronized (this) {
                    mFailures.merge(userId, 1, Integer::sum);
                }
            }
        }
    }

    /** Queues {@code userId}'s import on the background executor if due and not queued yet. */
    void importInBackground(int userId) {
        if (!isDue(userId)) return;
        synchronized (this) {
            if (!mQueued.add(userId)) return;
        }
        mBackground.execute(() -> {
            try {
                importNow(userId);
            } finally {
                synchronized (this) {
                    mQueued.remove(userId);
                }
            }
        });
    }

    private boolean isDue(int userId) {
        if (userId < 0 || mImported.isImported(userId)) return false;
        synchronized (this) {
            return mFailures.getOrDefault(userId, 0) < MAX_ATTEMPTS;
        }
    }
}
