// HyperGestures: the icon shape of recents (the card headers, split and floating views).
//
// Launcher3 masks app icons with the system's config_icon_mask unless an icon shape was picked.
// HyperOS 4 sets that mask to a plain square ("M50,0L100,0 100,100 0,100 0,0z", read from the
// device's framework-res.apk), because MiuiHome shapes its icons itself, so our icons came out
// square (owner, 2026-10-07). Unless a shape was picked, recents uses AOSP's rounded square
// instead: ShapesProvider's "square" path, a smooth-cornered square close to HyperOS's own icons.
// Off: Settings.Global hypergestures_icon_shape=0 (the system mask, as AOSP).
package com.android.quickstep;

import android.content.Context;

public final class HgIconShape {
    private HgIconShape() {}

    /** AOSP's rounded square (ShapesProvider SQUARE_PATH), 100x100. */
    public static final String ROUNDED_SQUARE =
            "M53.689 0.82 L53.689 .82 C67.434 .82 74.306 .82 79.758 2.978 87.649 6.103 93.897 "
                    + "12.351 97.022 20.242 99.18 25.694 99.18 32.566 99.18 46.311 V53.689 C99.18 "
                    + "67.434 99.18 74.306 97.022 79.758 93.897 87.649 87.649 93.897 79.758 97.022 "
                    + "74.306 99.18 67.434 99.18 53.689 99.18 H46.311 C32.566 99.18 25.694 99.18 "
                    + "20.242 97.022 12.351 93.897 6.103 87.649 2.978 79.758 .82 74.306 .82 67.434 "
                    + ".82 53.689 L.82 46.311 C.82 32.566 .82 25.694 2.978 20.242 6.103 12.351 "
                    + "12.351 6.103 20.242 2.978 25.694 .82 32.566 .82 46.311 .82Z";

    /** The mask to use when no shape was picked: the rounded square, or the system's if off. */
    public static String defaultMask(Context context, String systemMask) {
        return HgRecentsSettings.isOn(context, HgRecentsSettings.ICON_SHAPE)
                ? ROUNDED_SQUARE : systemMask;
    }
}
