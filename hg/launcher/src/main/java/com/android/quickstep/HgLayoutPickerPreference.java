// HyperGestures: the recents layout picker on the settings page (owner, 2026-10-31: "Option A"),
// in place of a list row and its dialog: three tiles side by side, Vertical, Horizontal and
// Stack, each with a moving miniature of its layout (HgLayoutPreviewView). A tap picks one at
// once; the picked tile has an accent ring, a check and its name in the accent colour. For
// TalkBack each tile is a radio button with its name and state. The page writes the setting
// (onPick says whether that worked) and sets the current choice on each resume.
package com.android.quickstep;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.launcher3.R;
import com.google.android.material.color.MaterialColors;

import java.util.function.Predicate;

final class HgLayoutPickerPreference extends Preference {
    private static final int[] MODES = {
            HgLayoutPreview.VERTICAL, HgLayoutPreview.HORIZONTAL, HgLayoutPreview.STACK};
    private static final int[] NAMES = {R.string.hg_settings_layout_vertical,
            R.string.hg_settings_layout_horizontal, R.string.hg_settings_layout_stack};

    /** Writes a setting value ("0", "1", "2"); false if that failed. */
    private final Predicate<String> mOnPick;
    /** The picked layout, HgLayoutPreview's; -1 for none (unreadable). */
    private int mMode = -1;

    HgLayoutPickerPreference(Context context, Predicate<String> onPick) {
        super(context);
        mOnPick = onPick;
        setLayoutResource(R.layout.hg_pref_layout_picker);
        setSelectable(false); // the tiles take the taps
    }

    /** Shows {@code value} ("0", "1", "2"; else none) as picked. */
    void setValue(String value) {
        int mode = HgLayoutPreview.mode(value);
        if (mode == mMode) return;
        mMode = mode;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        LinearLayout tiles = (LinearLayout) holder.findViewById(R.id.hg_layout_tiles);
        if (tiles == null) return;
        Context ctx = tiles.getContext();
        if (tiles.getChildCount() == 0) {
            LayoutInflater inflater = LayoutInflater.from(ctx);
            for (int i = 0; i < MODES.length; i++) {
                View tile = inflater.inflate(R.layout.hg_layout_tile, tiles, false);
                ((HgLayoutPreviewView) tile.findViewById(R.id.hg_layout_preview))
                        .setMode(MODES[i]);
                ((TextView) tile.findViewById(R.id.hg_layout_name)).setText(NAMES[i]);
                int mode = MODES[i];
                tile.setOnClickListener(v -> pick(mode));
                tiles.addView(tile);
            }
        }
        for (int i = 0; i < tiles.getChildCount(); i++) {
            bindTile(tiles.getChildAt(i), MODES[i] == mMode, ctx);
        }
    }

    private void pick(int mode) {
        if (mode == mMode) return;
        if (!mOnPick.test(HgLayoutPreview.value(mode))) return;
        mMode = mode;
        notifyChanged();
    }

    private static void bindTile(View tile, boolean picked, Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        float radius = 18 * density;
        int accent = MaterialColors.getColor(tile, androidx.appcompat.R.attr.colorPrimary);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.round((picked ? 2.5f : 1f) * density),
                picked ? accent : ctx.getColor(R.color.hg_preview_ring));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radius);
        mask.setColor(0xFFFFFFFF);
        int ripple = MaterialColors.getColor(tile, androidx.appcompat.R.attr.colorControlHighlight);
        tile.findViewById(R.id.hg_layout_frame).setBackground(
                new RippleDrawable(ColorStateList.valueOf(ripple), ring, mask));
        tile.findViewById(R.id.hg_layout_check).setVisibility(picked ? View.VISIBLE : View.GONE);
        TextView name = tile.findViewById(R.id.hg_layout_name);
        name.setTextColor(picked ? accent : MaterialColors.getColor(tile,
                android.R.attr.textColorSecondary));
        name.setTypeface(picked ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
        tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(picked);
            }
        });
        tile.setContentDescription(name.getText());
    }
}
