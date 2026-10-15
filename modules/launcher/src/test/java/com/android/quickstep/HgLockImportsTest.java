package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HgLockImportsTest {
    /** A fake HgTaskLockStore: records merges and the imported users. */
    private static final class Store {
        final Set<Integer> imported = Collections.synchronizedSet(new HashSet<>());
        final List<String> merges = Collections.synchronizedList(new ArrayList<>());

        void merge(int userId, List<String> packages) {
            merges.add(userId + ":" + packages);
            imported.add(userId);
        }
    }

    private static final List<Runnable> NO_RUN = new ArrayList<>();

    @Test public void importNowMergesOnceThenSkips() {
        Store store = new Store();
        AtomicInteger calls = new AtomicInteger();
        HgLockImports imports = new HgLockImports(u -> {
            calls.incrementAndGet();
            return List.of("com.a");
        }, store::merge, store.imported::contains, NO_RUN::add);
        imports.importNow(0);
        imports.importNow(0);
        assertEquals(1, calls.get());
        assertEquals(List.of("0:[com.a]"), store.merges);
    }

    @Test public void negativeUserIsNeverImported() {
        Store store = new Store();
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        HgLockImports imports = new HgLockImports(u -> {
            calls.incrementAndGet();
            return List.of();
        }, store::merge, store.imported::contains, queued::add);
        imports.importNow(-1);
        imports.importInBackground(-1);
        assertEquals(0, calls.get());
        assertTrue(queued.isEmpty());
    }

    /** A failed fetch (null) is retried, up to MAX_ATTEMPTS per process. */
    @Test public void failedFetchRetriedUpToMax() {
        Store store = new Store();
        AtomicInteger calls = new AtomicInteger();
        HgLockImports imports = new HgLockImports(u -> {
            calls.incrementAndGet();
            return null;
        }, store::merge, store.imported::contains, NO_RUN::add);
        for (int i = 0; i < HgLockImports.MAX_ATTEMPTS + 2; i++) imports.importNow(0);
        assertEquals(HgLockImports.MAX_ATTEMPTS, calls.get());
        assertTrue(store.merges.isEmpty());
    }

    /** A retry after a failure succeeds and merges. */
    @Test public void retryAfterFailureMerges() {
        Store store = new Store();
        AtomicInteger calls = new AtomicInteger();
        HgLockImports imports = new HgLockImports(
                u -> calls.incrementAndGet() == 1 ? null : List.of("com.b"),
                store::merge, store.imported::contains, NO_RUN::add);
        imports.importNow(0);
        imports.importNow(0);
        imports.importNow(0);
        assertEquals(2, calls.get());
        assertEquals(List.of("0:[com.b]"), store.merges);
    }

    /** Card binds queue one background import per user, not one per bind. */
    @Test public void backgroundImportQueuedOnce() {
        Store store = new Store();
        List<Runnable> queued = new ArrayList<>();
        HgLockImports imports = new HgLockImports(u -> List.of("com.a"), store::merge,
                store.imported::contains, queued::add);
        imports.importInBackground(0);
        imports.importInBackground(0);
        imports.importInBackground(10);
        assertEquals(2, queued.size());
        queued.get(0).run();
        imports.importInBackground(0); // imported now: nothing more to queue
        assertEquals(2, queued.size());
    }

    /**
     * Clear all right after the store was created: the synchronous import must wait for the
     * background one in flight and see its result, not return at once.
     */
    @Test public void importNowWaitsForInFlightBackgroundImport() throws Exception {
        Store store = new Store();
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HgLockImports imports = new HgLockImports(u -> {
            calls.incrementAndGet();
            fetching.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of("com.locked");
        }, store::merge, store.imported::contains, r -> new Thread(r).start());
        imports.importInBackground(0);
        assertTrue(fetching.await(5, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> imports.importNow(0));
        waiter.start();
        waiter.join(200);
        assertTrue("importNow returned while the import was in flight", waiter.isAlive());
        release.countDown();
        waiter.join(5000);
        assertTrue(store.imported.contains(0));
        assertEquals(1, calls.get());
    }
}
