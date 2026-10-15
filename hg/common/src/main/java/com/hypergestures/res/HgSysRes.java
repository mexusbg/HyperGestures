// HyperGestures: private framework resources, resolved by name at runtime.
//
// The app compiles against the phone's own framework.jar (a stub), so a
// com.android.internal.R.<type>.<name> constant in code is inlined as THIS firmware's numeric ID.
// Private IDs are not stable: after a HyperOS update the same number can name another resource
// (wrong value) or none (Resources.NotFoundException). Code that needs a private framework
// resource asks for it by name here instead; a name that does not resolve gives the caller's
// fallback, never an exception. docs/research/private-resource-ids.md lists the call sites, and
// the build check tools/hgtools/private_res_ids.py fails on any private ID left in the dex.
//
// Lives in :flags (the bottom of the module graph) so every module that compiles AOSP code with
// such references (launcher, wmshell-shared, compose-libs) can reach it.
package com.hypergestures.res;

import android.content.Context;
import android.content.res.Resources;

import java.util.concurrent.ConcurrentHashMap;

public final class HgSysRes {
    private HgSysRes() {}

    /** Resources#getIdentifier's shape, so JVM tests can stand in for the framework. */
    public interface Lookup {
        int getIdentifier(String name, String type, String pkg);
    }

    private static final Lookup SYSTEM =
            (name, type, pkg) -> Resources.getSystem().getIdentifier(name, type, pkg);

    private static volatile Lookup sLookup = SYSTEM;
    /** "type/name" → resolved ID; 0 caches a miss. Framework IDs don't change within a boot. */
    private static final ConcurrentHashMap<String, Integer> sCache = new ConcurrentHashMap<>();

    /**
     * The framework ("android" package) resource ID of {@code type/name} on this device, or
     * {@code fallback} when the name does not exist (or cannot be looked up). Cached.
     */
    public static int id(String type, String name, int fallback) {
        Integer cached = sCache.get(type + "/" + name);
        int id;
        if (cached != null) {
            id = cached;
        } else {
            try {
                id = sLookup.getIdentifier(name, type, "android");
            } catch (RuntimeException e) {
                id = 0;
            }
            sCache.put(type + "/" + name, id);
        }
        return id != 0 ? id : fallback;
    }

    /** Framework {@code bool/name}, or {@code dflt} if it is missing. */
    public static boolean getBoolean(Resources res, String name, boolean dflt) {
        int id = id("bool", name, 0);
        if (id == 0) return dflt;
        try {
            return res.getBoolean(id);
        } catch (Resources.NotFoundException e) {
            return dflt;
        }
    }

    /** Framework {@code string/name}, or {@code dflt} if it is missing. */
    public static String getString(Resources res, String name, String dflt) {
        int id = id("string", name, 0);
        if (id == 0) return dflt;
        try {
            return res.getString(id);
        } catch (Resources.NotFoundException e) {
            return dflt;
        }
    }

    /** Framework {@code color/name} under the context's theme, or {@code dflt} if it is missing. */
    public static int getColor(Context context, String name, int dflt) {
        int id = id("color", name, 0);
        if (id == 0) return dflt;
        try {
            return context.getResources().getColor(id, context.getTheme());
        } catch (Resources.NotFoundException e) {
            return dflt;
        }
    }

    /** Swaps the framework lookup (null restores the real one) and clears the cache. */
    public static void setLookupForTest(Lookup lookup) {
        sLookup = lookup != null ? lookup : SYSTEM;
        sCache.clear();
    }
}
