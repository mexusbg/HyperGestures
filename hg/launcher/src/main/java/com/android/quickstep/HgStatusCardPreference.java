// HyperGestures: the status card at the top of the settings page (HgSettingsFragment): the
// preference's icon, title and note as any row's, plus the app's version pill and the status list
// (HgStatusCard), one hg_status_row per fact, its dot in the colour of its level. The page sets
// the rows once its helper thread has read them; until then the list stays hidden.
package com.android.quickstep;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.List;

final class HgStatusCardPreference extends Preference {
    /** One row of the list: its label, value and HgStatusCard level. */
    static final class Item {
        final String label;
        final String value;
        final int level;

        Item(String label, String value, int level) {
            this.label = label;
            this.value = value;
            this.level = level;
        }
    }

    private String mVersion;
    private List<Item> mItems = new ArrayList<>();

    HgStatusCardPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.hg_pref_status);
        setSelectable(false);
    }

    /** The pill's text ("v0.4.0-beta"); null hides it. */
    void setVersion(String version) {
        mVersion = version;
        notifyChanged();
    }

    void setItems(List<Item> items) {
        mItems = new ArrayList<>(items);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView pill = (TextView) holder.findViewById(R.id.hg_status_version);
        if (pill != null) {
            pill.setText(mVersion);
            pill.setVisibility(mVersion == null ? View.GONE : View.VISIBLE);
        }
        LinearLayout rows = (LinearLayout) holder.findViewById(R.id.hg_status_rows);
        if (rows == null) return;
        rows.setVisibility(mItems.isEmpty() ? View.GONE : View.VISIBLE);
        // Rows and dividers alternate (row, line, row...); the ones a previous bind made are
        // rebound in place, missing ones added and extra ones removed, so a rebind (every resume
        // and status refresh) re-inflates nothing it already has.
        Context ctx = rows.getContext();
        LayoutInflater inflater = null;
        int divider = ctx.getResources().getDimensionPixelSize(R.dimen.hg_status_divider);
        for (int i = 0; i < mItems.size(); i++) {
            if (i > 0 && rows.getChildAt(2 * i - 1) == null) {
                View line = new View(ctx);
                line.setBackgroundColor(ctx.getColor(R.color.hg_status_divider));
                rows.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, divider));
            }
            View row = rows.getChildAt(2 * i);
            if (row == null) {
                if (inflater == null) inflater = LayoutInflater.from(ctx);
                row = inflater.inflate(R.layout.hg_status_row, rows, false);
                row.setScreenReaderFocusable(true); // TalkBack reads label and value as one
                rows.addView(row);
            }
            Item item = mItems.get(i);
            ((TextView) row.findViewById(R.id.hg_status_label)).setText(item.label);
            ((TextView) row.findViewById(R.id.hg_status_value)).setText(item.value);
            row.findViewById(R.id.hg_status_dot).getBackground().mutate()
                    .setTint(ctx.getColor(dotColor(item.level)));
        }
        int used = Math.max(0, 2 * mItems.size() - 1);
        if (rows.getChildCount() > used) rows.removeViews(used, rows.getChildCount() - used);
    }

    private static int dotColor(int level) {
        switch (level) {
            case HgStatusCard.OK: return R.color.hg_status_ok;
            case HgStatusCard.PENDING: return R.color.hg_status_pending;
            case HgStatusCard.FAILED: return R.color.hg_status_failed;
            default: return R.color.hg_status_neutral;
        }
    }
}
