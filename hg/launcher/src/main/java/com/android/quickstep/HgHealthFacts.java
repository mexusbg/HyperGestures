// HyperGestures: the facts the module's health and bind checks need, from the side that owns
// them. Today's checks parse text dumps (SystemUI's LauncherProxyService section for "connected,
// pkg=com.hypergestures", `dumpsys input` for our "[Gesture Monitor] swipe-up"); a firmware that
// changes either format matches nothing, and the module would roll itself back at every boot.
// So we say it ourselves, in Settings.Global (we hold WRITE_SECURE_SETTINGS; root reads it with
// `settings get`, hg_app_fact, and never has to look into our data dir):
//   hypergestures_provider_bound   SystemUI bound TouchInteractionService and handed it its
//                                  proxy (TISBinder.onInitialize); cleared when SystemUI unbinds
//                                  it (TouchInteractionService.onUnbind, and onDestroy, which also
//                                  runs when SystemUI dies);
//   hypergestures_gesture_monitor  our swipe-up input monitor is registered on the default
//                                  display (TouchInteractionHandler.InputResource); cleared when
//                                  it is disposed.
// Each value is "<bootId> <pid> <uptimeMs>": the module counts it only for this boot and while the
// pid is still our process, so a value left by a dead process or an earlier boot says nothing;
// the dumps stay its fallback and a once-a-boot cross-check. Writes run in order on one
// background thread and fail soft (a lost write only means the dumps decide, as before); a
// failed clear is retried once and logged, since a lost one would leave "bound" standing while
// our process lives.
// Off switch hypergestures_health_facts=0 (HgRecentsSettings.HEALTH_FACTS): read at each publish;
// while it is 0 a publish writes nothing and clears both values instead. The module reads the
// switch once a boot (hg_facts_on) and, while it is 0, ignores both values whatever they say, so
// its checks go by the dumps alone; set it and reboot for the module to follow.
package com.android.quickstep;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class HgHealthFacts {
    private static final String TAG = "HgHealthFacts";

    /** SystemUI has bound us (hg_app_fact's HG_FACT_BOUND). */
    public static final String PROVIDER_BOUND = "hypergestures_provider_bound";
    /** Our swipe-up gesture monitor is registered (hg_app_fact's HG_FACT_MONITOR). */
    public static final String GESTURE_MONITOR = "hypergestures_gesture_monitor";
    /** The input monitor's name, as TouchInteractionHandler registers it and dumpsys lists it. */
    public static final String MONITOR_NAME = "swipe-up";

    /** One thread, so a clear and the write after it land in the order they were made. */
    private static final Executor WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hg-health-facts");
        t.setDaemon(true);
        return t;
    });

    private HgHealthFacts() {}

    /** The value for this boot's id, our pid and the uptime, as hg_app_fact parses it. */
    static String value(String bootId, int pid, long uptimeMs) {
        return bootId + " " + pid + " " + uptimeMs;
    }

    /** Says {@code key} holds now, from this process (off the main thread, fail-soft). */
    public static void publish(Context context, String key) {
        int pid = Process.myPid();
        long uptimeMs = SystemClock.elapsedRealtime();
        ContentResolver cr = context.getApplicationContext().getContentResolver();
        WRITER.execute(() -> {
            if (!HgRecentsSettings.isOn(get(cr, HgRecentsSettings.HEALTH_FACTS))) {
                // Switched off: neither value may stand, whichever was published before.
                clearNow(cr, PROVIDER_BOUND);
                clearNow(cr, GESTURE_MONITOR);
                return;
            }
            String bootId = HgLogCollector.bootId();
            if (bootId == null) {
                clearNow(cr, key); // the module could not tell this boot's value apart
            } else {
                put(cr, key, value(bootId, pid, uptimeMs));
            }
        });
    }

    /** Says {@code key} no longer holds (off the main thread, fail-soft). */
    public static void clear(Context context, String key) {
        ContentResolver cr = context.getApplicationContext().getContentResolver();
        WRITER.execute(() -> clearNow(cr, key));
    }

    /** Clears {@code key}, once more if the first try fails (a lost clear leaves it standing). */
    private static void clearNow(ContentResolver cr, String key) {
        if (put(cr, key, null)) return;
        if (put(cr, key, null)) {
            Log.i(TAG, key + ": cleared at the second try");
        } else {
            Log.w(TAG, key + ": not cleared; the module counts it until our process dies");
        }
    }

    private static String get(ContentResolver cr, String key) {
        try {
            return Settings.Global.getString(cr, key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Writes {@code value} (null: clears); false, logged, if it failed. */
    private static boolean put(ContentResolver cr, String key, String value) {
        try {
            if (Settings.Global.putString(cr, key, value)) return true;
            Log.w(TAG, key + ": not written");
        } catch (RuntimeException e) {
            Log.w(TAG, key + ": " + e);
        }
        return false;
    }
}
