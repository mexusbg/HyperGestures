// HyperGestures: whether recents can blur right now, one answer for the blurred background
// (HgRecentsBlur's DepthController), the dim over it (HgRecentsBlur.scrimColor), and the frosted
// elements drawn over it (the trash button, the hold buttons, the lock badge, the corner
// targets). The module may be installed on
// phones without blur: HyperOS turns it off on lower-end devices, and battery saver turns it off
// at runtime. Then the frost (translucent white over a blurred background) is unreadable over a
// sharp wallpaper, so those elements take solid fills and their cheap drawing paths.
//
// The decision combines AOSP's and MiuiHome's (MiuiHome 7.50, common.utils.BlurUtilities):
// - AOSP: WindowManager.isCrossWindowBlurEnabled() (ro.surface_flinger.supports_background_blur,
//   the "disable window blurs" developer switch, battery saver, critical thermal), followed live
//   through CrossWindowBlurListeners.
// - MiuiHome isBlurSupported(): persist.sys.background_blur_supported (read once, default false;
//   HyperOS sets it per device) and Settings.Secure background_blur_enable == 1 (HyperOS's blur
//   switch, followed live here by a ContentObserver). MiuiHome reads an absent key as 0 (off);
//   we let only a present value other than "1" veto, so an absent or unreadable key never takes
//   blur away from a phone that has it (the other terms still decide there).
// - MiuiHome isThreeLayerBlurSupported() (its recents blur radius: 120 or 0) also excludes a list
//   of devices by Build.DEVICE (NO_BLUR_DEVICES). Its other terms are not ours: HyperOS 2+ and
//   Android V+ always hold here, and it also skips blur on HyperOS beta builds (IS_DEV_VERSION),
//   which is a channel quirk, not a capability, so we keep blur there.
// The pure decision (blurAvailable, frosted) is unit-tested; the state is cached and updated only
// by those two listeners while a RecentsActivity tracks it (no polling).
//
// Settings.Global hypergestures_blur_fallback=0 turns this off: the blur follows only the
// system's cross-window switch and the frosted elements always draw their glass (0.3.9.29).
package com.android.quickstep;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.CrossWindowBlurListeners;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.util.ListenableRef;
import com.android.launcher3.util.MutableListenableRef;
import com.android.launcher3.util.SafeCloseable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class HgBlurSupport {
    private HgBlurSupport() {}

    private static final String TAG = "HgBlurSupport";
    /** [V] MiuiHome's per-device blur capability, a system property (default false). */
    static final String MIUI_SUPPORTED_PROP = "persist.sys.background_blur_supported";
    /** [V] HyperOS's blur switch, Settings.Secure, 1 = on (MiuiHome's default 0). */
    static final String MIUI_ENABLE = "background_blur_enable";
    /**
     * The MIUI_ENABLE value when it could not be read. Like an absent key it does not veto
     * (MiuiHome treats absent as off; we do not, since the owner's phone was never checked).
     */
    static final String UNREADABLE = "<unreadable>";
    /** [V] MiuiHome isNotSupportBlurDevice(): Build.DEVICE names without recents blur. */
    static final List<String> NO_BLUR_DEVICES = Arrays.asList("chenfeng", "peridot", "thor",
            "unicorn", "mayfly", "diting", "mondrian", "zizhan");

    private static final MutableListenableRef<Boolean> sBlur = new MutableListenableRef<>(true);
    private static boolean sInitialized;
    private static boolean sRecentsBlurOn = true;
    private static boolean sPolicyOn = true;
    private static boolean sCrossWindow = true;
    @Nullable private static String sSupportedProp;
    @Nullable private static String sMiuiEnable = UNREADABLE;
    private static int sTrackers;

    /**
     * Whether recents blurs its background: the owner's recents blur on, the system's
     * cross-window blur on, and (unless {@code policyOn} is false) MiuiHome's terms.
     */
    static boolean blurAvailable(boolean recentsBlurOn, boolean crossWindow, boolean policyOn,
            @Nullable String supportedProp, @Nullable String miuiEnable,
            @Nullable String device) {
        if (!recentsBlurOn || !crossWindow) return false;
        if (!policyOn) return true;
        return parseBoolean(supportedProp) && !miuiSwitchVetoes(miuiEnable)
                && (device == null || !NO_BLUR_DEVICES.contains(device));
    }

    /**
     * Whether HyperOS's blur switch (MIUI_ENABLE's raw value) turns blur off: only when the key
     * is present and not "1" (MiuiHome's on value). Absent (null) or UNREADABLE: no veto.
     */
    static boolean miuiSwitchVetoes(@Nullable String miuiEnable) {
        if (miuiEnable == null || UNREADABLE.equals(miuiEnable)) return false;
        return !"1".equals(miuiEnable.trim());
    }

    /** Whether the frosted elements draw their glass: blur behind them, or the policy off. */
    static boolean frosted(boolean policyOn, boolean blurAvailable) {
        return !policyOn || blurAvailable;
    }

    /** SystemProperties.getBoolean's parsing: 1, y, yes, on, true; anything else is false. */
    static boolean parseBoolean(@Nullable String value) {
        if (value == null) return false;
        switch (value) {
            case "1": case "y": case "yes": case "on": case "true":
                return true;
            default:
                return false;
        }
    }

    /** The recents blur's state for its DepthController: true while recents can blur. */
    public static ListenableRef<Boolean> blurState(Context context) {
        ensureInitialized(context);
        return sBlur;
    }

    /** Whether recents can blur right now (cached). */
    public static boolean isBlurAvailable(Context context) {
        ensureInitialized(context);
        return sBlur.getValue();
    }

    /** As isBlurAvailable, once a RecentsActivity tracks the state (track). */
    public static boolean isBlurAvailableNow() {
        return sBlur.getValue();
    }

    /** As isFrosted, once a RecentsActivity tracks the state (track). */
    public static boolean isFrostedNow() {
        return frosted(sPolicyOn, sBlur.getValue());
    }

    /** Whether hypergestures_blur_fallback is on, as read when recents was created. */
    public static boolean isPolicyOnNow() {
        return sPolicyOn;
    }

    /** Whether the frosted elements draw their glass now (cached); solid fills otherwise. */
    public static boolean isFrosted(Context context) {
        ensureInitialized(context);
        return frosted(sPolicyOn, sBlur.getValue());
    }

    /**
     * RecentsActivity.onCreate: reads the switches and follows the system's and HyperOS's blur
     * switches until the activity is destroyed. {@code recentsBlurOn}: the owner's recents blur
     * (hypergestures_recents_blur); without it nothing blurs behind recents.
     */
    public static SafeCloseable track(Context context, boolean recentsBlurOn) {
        Context app = context.getApplicationContext();
        sInitialized = true;
        sRecentsBlurOn = recentsBlurOn;
        sPolicyOn = HgRecentsSettings.isOn(app, HgRecentsSettings.BLUR_FALLBACK);
        sSupportedProp = readProp();
        sMiuiEnable = readMiuiEnable(app.getContentResolver());
        sCrossWindow = readCrossWindow();
        update("recents created");

        Consumer<Boolean> crossWindowCallback = enabled -> {
            sCrossWindow = enabled;
            update("cross-window blur " + (enabled ? "on" : "off"));
        };
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                sMiuiEnable = readMiuiEnable(app.getContentResolver());
                update(MIUI_ENABLE + "=" + sMiuiEnable);
            }
        };
        CrossWindowBlurListeners listeners = null;
        try {
            listeners = CrossWindowBlurListeners.getInstance();
            listeners.addListener(app.getMainExecutor(), crossWindowCallback);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "no cross-window blur listener", e);
            listeners = null;
        }
        try {
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(MIUI_ENABLE), false, observer);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot observe " + MIUI_ENABLE, e);
        }
        sTrackers++;
        CrossWindowBlurListeners registered = listeners;
        return () -> {
            sTrackers--;
            if (registered != null) {
                try {
                    registered.removeListener(crossWindowCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "removing the cross-window blur listener failed", e);
                }
            }
            try {
                app.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException e) {
                Log.w(TAG, "removing the " + MIUI_ENABLE + " observer failed", e);
            }
        };
    }

    /**
     * Recents opens (HgRecentsDepthController.setActivityStarted): the owner's recents blur
     * (hypergestures_recents_blur) read again, so the page's switch applies the next time
     * recents opens, without a reboot. One Settings read; the state changes only with it.
     */
    public static void refreshRecentsBlur(Context context) {
        boolean on;
        try {
            on = HgRecentsSettings.isOn(context, HgRecentsSettings.BLUR);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read " + HgRecentsSettings.BLUR, e);
            return;
        }
        if (on == sRecentsBlurOn) return;
        sRecentsBlurOn = on;
        update("recents blur switch " + (on ? "on" : "off"));
    }

    /**
     * Gives {@code view} the round frosted background (hg_glass_circle), or the solid one
     * (hg_solid_circle) when blur is unavailable, and keeps it current while it is attached.
     */
    public static void followGlassCircle(View view) {
        applyGlassCircle(view);
        View.OnAttachStateChangeListener follower = new View.OnAttachStateChangeListener() {
            @Nullable private SafeCloseable mSubscription;

            @Override
            public void onViewAttachedToWindow(View v) {
                if (mSubscription != null) return;
                mSubscription = sBlur.forEach(v.getContext().getMainExecutor(), blur -> {
                    applyGlassCircle(v);
                    return null;
                });
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (mSubscription != null) mSubscription.close();
                mSubscription = null;
            }
        };
        view.addOnAttachStateChangeListener(follower);
        if (view.isAttachedToWindow()) follower.onViewAttachedToWindow(view);
    }

    private static void applyGlassCircle(View view) {
        int res = isFrosted(view.getContext()) ? R.drawable.hg_glass_circle
                : R.drawable.hg_solid_circle;
        Object current = view.getTag(R.id.hg_glass_circle_res);
        if (current instanceof Integer i && i == res) return;
        view.setTag(R.id.hg_glass_circle_res, res);
        view.setBackgroundResource(res);
    }

    /** Before any recents tracked the state (a cold start mid-gesture): read it once. */
    private static void ensureInitialized(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        Context app = context.getApplicationContext();
        sRecentsBlurOn = HgRecentsSettings.isOn(app, HgRecentsSettings.BLUR);
        sPolicyOn = HgRecentsSettings.isOn(app, HgRecentsSettings.BLUR_FALLBACK);
        sSupportedProp = readProp();
        sMiuiEnable = readMiuiEnable(app.getContentResolver());
        sCrossWindow = readCrossWindow();
        update("first use");
    }

    private static void update(String why) {
        boolean blur = blurAvailable(sRecentsBlurOn, sCrossWindow, sPolicyOn, sSupportedProp,
                sMiuiEnable, Build.DEVICE);
        Log.i(TAG, "blur " + (blur ? "available" : "unavailable") + " (" + why
                + "): recents blur " + (sRecentsBlurOn ? "on" : "off")
                + ", cross-window " + (sCrossWindow ? "on" : "off")
                + ", " + MIUI_SUPPORTED_PROP + "=" + sSupportedProp
                + ", " + MIUI_ENABLE + "=" + sMiuiEnable + ", device=" + Build.DEVICE
                + ", policy " + (sPolicyOn ? "on" : "off") + ", trackers=" + sTrackers);
        if (sBlur.getValue() != blur) sBlur.dispatchValue(blur);
    }

    @Nullable
    private static String readProp() {
        try {
            return SystemProperties.get(MIUI_SUPPORTED_PROP, "");
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "cannot read " + MIUI_SUPPORTED_PROP, e);
            return "true"; // fail soft: AOSP's answer stands
        }
    }

    @Nullable
    private static String readMiuiEnable(ContentResolver resolver) {
        try {
            return Settings.Secure.getString(resolver, MIUI_ENABLE);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read " + MIUI_ENABLE, e);
            return UNREADABLE;
        }
    }

    private static boolean readCrossWindow() {
        try {
            return CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "cannot read the cross-window blur state", e);
            return false;
        }
    }
}
