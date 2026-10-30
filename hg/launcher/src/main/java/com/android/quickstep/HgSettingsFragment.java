// HyperGestures: the settings page's list (HgSettingsActivity). Each switch reads and writes the
// existing setting directly: our Settings.Global hypergestures_* off switches ("0" off, anything
// else or unset on; HgRecentsSettings), the opt-in hypergestures_hide_gesture_line ("1" on,
// then applied through HgHideGestureLine) and hypergestures_notification_icons ("1" on; read by
// our LSPosed hooks in SystemUI and the AOD: their own APK, com.hypergestures.notif, which shares
// only NotifKeys with us), HyperOS's Settings.System
// miui_recents_show_mem_info, AOSP's Settings.Secure back_gesture_inset_scale_left/right and the
// System launcher's own Global hide_gesture_line and Secure NavLongPress (HgNavLine).
// Nothing is stored in the app, so adb and the page always agree; the values are read again
// whenever the page resumes. Writes need WRITE_SECURE_SETTINGS (Global, Secure) and
// WRITE_SETTINGS (System), both granted; a failed write leaves the switch as it was and toasts.
// Plus Save logs (HgLogCollector), versions, the author, the license (CC
// BY-NC-SA 4.0, a tap opens its deed) and a Ko-fi link (the browser; a toast without one). Sections: Recents and Gestures (ours, only while they
// run), System (works with any home app: "Hide navigation line", the System launcher's; while
// it is off "Long press navigation line" and "Hide navigation line (alternative)", our overlay;
// "Fix notification icons", "Fix App info back": HgAppInfoTrampoline's component follows it),
// Logs and About. Whether ours run is this boot's state as the module
// publishes it (HgModuleStatus), read with the HOME holder whenever the page resumes, its opening
// included: dormant (HyperOS's own gestures and recents), rolled back, not set up or refused
// hide Recents and Gestures behind a note, and a HOME changed since the boot adds "reboot"
// (HgSettingsSections). HOME is HgHome's, as everywhere in the app (the HOME role holder; the
// chooser and a setup wizard are none); without a status for this boot it alone decides.
// Looks: a status card at the top (running, standing by or not running, with the note, the app's
// version and the status list, HgStatusCard, read on the helper thread at each resume), then each
// section's rows on one rounded card under a quiet header (HgSettingsCards; the rows' layouts and
// switches are HgPreferenceTheme's), the layout picked from moving previews, web links marked by an open mark.
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;
import com.android.launcher3.util.Executors;
import com.android.quickstep.interaction.HgBackPolicy;
import com.google.android.material.color.MaterialColors;
import com.hypergestures.notif.NotifKeys;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class HgSettingsFragment extends PreferenceFragmentCompat {
    private static final String TAG = "HgSettings";

    /** The app preference (default SharedPreferences) behind "Recents and gestures only". */
    static final String PREF_LOG_FILTER = "hg_logs_filter";

    /** The order of About's author, license and Ko-fi lines: after everything added in order. */
    private static final int ABOUT_END_ORDER = 1000;

    /** A save is running (it outlives a recreated page); main thread only. */
    private static boolean sSaving;
    /** The page shown last, told when a save ends. */
    private static WeakReference<HgSettingsFragment> sShown = new WeakReference<>(null);

    /** Each preference's read of its setting, run on every resume. */
    private final List<Runnable> mRefreshers = new ArrayList<>();
    private Preference mSave;
    private SwitchPreferenceCompat mFilter;
    /** The page's parts, by HgSettingsSections' ids, shown or hidden for this boot's state. */
    private final Preference[] mSections = new Preference[HgSettingsSections.ABOUT + 1];
    /** The status card (HgStatusCardPreference). */
    private HgStatusCardPreference mStatusCard;
    /** Our own versionCode; -1 if unreadable. */
    private long mAppCode = -1;
    /** The status card's kind (HgSettingsSections.STATUS_*), its background's. */
    private int mStatusKind = HgSettingsSections.STATUS_ON;
    /** Disabled and shown off while the System launcher's hide is on (updateCircleToSearch). */
    private SwitchPreferenceCompat mCircleToSearch;
    /** Shown only while the System launcher's line is visible (HgNavLine.dependentsShown). */
    private Preference mAltHide;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context ctx = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
        setPreferenceScreen(screen);
        if (savedInstanceState == null) {
            // The hide-gesture-line overlay follows its setting whatever HOME is; with MiuiHome as
            // HOME our gesture service (whose start syncs it) never runs, so each opening does.
            HgHideGestureLine.syncLater(ctx);
            // The App info trampoline's component follows its switch the same way (adb).
            HgAppInfoTrampoline.syncComponentLater(ctx);
        }
        HgSettingsMigration.run(ctx); // layout copied from HyperOS once; dropped switch keys

        mStatusCard = new HgStatusCardPreference(ctx); // follows the mode (updateSections)
        screen.addPreference(mStatusCard);
        mSections[HgSettingsSections.STATUS] = mStatusCard;

        PreferenceGroup recents = category(screen, R.string.hg_settings_recents);
        mSections[HgSettingsSections.RECENTS] = recents;
        addLayout(recents);
        addGlobalSwitch(recents, HgRecentsSettings.BLUR,
                R.string.hg_settings_blur, R.string.hg_settings_blur_summary);
        addGlobalSwitch(recents, HgRecentsSettings.CARD_LOCK_SWIPE,
                R.string.hg_settings_lock_swipe, R.string.hg_settings_lock_swipe_summary);
        addGlobalSwitch(recents, HgRecentsSettings.CORNER_DRAG,
                R.string.hg_settings_corner_drag, R.string.hg_settings_corner_drag_summary);
        ContentResolver cr = ctx.getContentResolver();
        addSwitch(recents, R.string.hg_settings_show_mem, R.string.hg_settings_show_mem_summary,
                () -> Settings.System.getInt(cr, HgRamIndicator.SHOW_MEM_INFO, 0) != 0,
                on -> Settings.System.putInt(cr, HgRamIndicator.SHOW_MEM_INFO, on ? 1 : 0));
        addGlobalSwitch(recents, HgRecentsSettings.CLEAR_KILLS,
                R.string.hg_settings_clear_kills, R.string.hg_settings_clear_kills_summary);
        addGlobalSwitch(recents, HgRecentsSettings.DISMISS_KILLS,
                R.string.hg_settings_dismiss_kills, R.string.hg_settings_dismiss_kills_summary);

        PreferenceGroup gestures = category(screen, R.string.hg_settings_gestures);
        mSections[HgSettingsSections.GESTURES] = gestures;
        addGlobalSwitch(gestures, HgHomeBack.SETTING,
                R.string.hg_settings_home_back, R.string.hg_settings_home_back_summary);
        addGlobalSwitch(gestures, HgRecentsSettings.BACK_STYLE,
                R.string.hg_settings_back_style, R.string.hg_settings_back_style_summary);
        addSensitivity(gestures, HgBackPolicy.INSET_SCALE_LEFT, R.string.hg_settings_back_left);
        addSensitivity(gestures, HgBackPolicy.INSET_SCALE_RIGHT, R.string.hg_settings_back_right);

        // Works with any home app, so it stays while HyperOS's gestures and recents run.
        PreferenceGroup system = category(screen, R.string.hg_settings_system);
        mSections[HgSettingsSections.SYSTEM] = system;
        addNavLineHide(system);
        mCircleToSearch = addCircleToSearch(system);
        mAltHide = addHideLine(system);
        addNotificationIcons(system);
        addAppInfoFix(system);

        PreferenceGroup logs = category(screen, R.string.hg_settings_logs);
        mSections[HgSettingsSections.LOGS] = logs;
        Preference privacy = new Preference(ctx);
        privacy.setSummary(R.string.hg_settings_logs_privacy);
        privacy.setSelectable(false);
        privacy.setIconSpaceReserved(false);
        logs.addPreference(privacy);
        mFilter = new SwitchPreferenceCompat(ctx); // an on/off, as every other row
        mFilter.setKey(PREF_LOG_FILTER);
        mFilter.setDefaultValue(true);
        mFilter.setTitle(R.string.hg_settings_logs_filter);
        mFilter.setSummary(R.string.hg_settings_logs_filter_summary);
        mFilter.setIconSpaceReserved(false);
        logs.addPreference(mFilter);
        mSave = new Preference(ctx);
        mSave.setTitle(R.string.hg_settings_logs_save);
        mSave.setIconSpaceReserved(false);
        mSave.setOnPreferenceClickListener(p -> {
            startSave();
            return true;
        });
        logs.addPreference(mSave);

        PreferenceGroup about = category(screen, R.string.hg_settings_about);
        mSections[HgSettingsSections.ABOUT] = about;
        long appCode = -1;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            appCode = pi.getLongVersionCode();
            mAppCode = appCode;
            mStatusCard.setVersion(getString(R.string.hg_status_version, pi.versionName));
            addInfo(about, R.string.hg_settings_app_version, getString(R.string.hg_settings_version,
                    pi.versionName, (int) appCode));
        } catch (Exception e) {
            Log.w(TAG, "own package info unreadable: " + e);
        }
        // Last in About, after the module version lines added later (explicit order).
        addInfo(about, R.string.hg_settings_author, getString(R.string.hg_settings_author_name))
                .setOrder(ABOUT_END_ORDER);
        Preference license = new Preference(ctx);
        license.setTitle(R.string.hg_settings_license);
        license.setSummary(R.string.hg_settings_license_name);
        license.setIconSpaceReserved(false);
        license.setWidgetLayoutResource(R.layout.hg_pref_open);
        license.setOrder(ABOUT_END_ORDER + 1);
        license.setOnPreferenceClickListener(p -> {
            openLink(getString(R.string.hg_settings_license_url));
            return true;
        });
        about.addPreference(license);
        Preference kofi = new Preference(ctx);
        kofi.setTitle(R.string.hg_settings_kofi);
        kofi.setSummary(R.string.hg_settings_kofi_summary);
        kofi.setIconSpaceReserved(false);
        kofi.setWidgetLayoutResource(R.layout.hg_pref_open);
        kofi.setOrder(ABOUT_END_ORDER + 2);
        kofi.setOnPreferenceClickListener(p -> {
            openLink(getString(R.string.hg_settings_kofi_url));
            return true;
        });
        about.addPreference(kofi);
        // The module version (this boot's value service.sh writes, else its log copy's end), an
        // installed update waiting for a reboot (customize.sh's value) and an update the system
        // did not take (the module runs a newer versionCode than ours), off the main thread;
        // "unknown" when nothing names it (or our boot id is unreadable).
        Context app = ctx.getApplicationContext();
        long ownCode = appCode;
        Executors.THREAD_POOL_EXECUTOR.execute(() -> {
            HgLogCollector.RunningModule running = HgLogCollector.runningModule(app);
            HgLogCollector.ModuleVersion update = HgLogCollector.moduleUpdate(app, running);
            String behind = ownCode < 0 ? null : HgModuleStatus.appBehind(ownCode, running);
            Executors.MAIN_EXECUTOR.execute(() -> {
                if (!isAdded()) return;
                String module = running.version == null
                        ? getString(R.string.hg_settings_module_unknown)
                        : running.running() ? running.version.display()
                        : getString(R.string.hg_settings_module_not_running,
                                running.version.display());
                String summary = update == null ? module
                        : getString(R.string.hg_settings_module_update, module, update.name);
                addInfo(about, R.string.hg_settings_module_version, summary);
                if (behind != null) {
                    addInfo(about, R.string.hg_settings_update_not_applied, getString(
                            R.string.hg_settings_update_not_applied_summary, ownCode, behind));
                }
            });
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null); // the cards part the sections
        RecyclerView list = getListView();
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0,
                getResources().getDimensionPixelSize(R.dimen.hg_settings_list_bottom));
    }

    /** The list's adapter, drawing the cards behind the rows as it binds them (styleCard). */
    @SuppressLint("RestrictedApi") // PreferenceGroupAdapter: the only way to a row's preference
    @NonNull
    @Override
    protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen screen) {
        return new PreferenceGroupAdapter(screen) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                styleCard(this, holder, position);
            }
        };
    }

    /**
     * A row's part of its section's card (HgSettingsCards): the card's colour, rounded where the
     * card begins or ends, with the touch ripple inside the same shape; the status card gets its
     * kind's gradient. Headers stay bare. A rebind restores the adapter's own background first,
     * so this runs on every bind.
     */
    @SuppressLint("RestrictedApi")
    private void styleCard(PreferenceGroupAdapter adapter, PreferenceViewHolder holder,
            int position) {
        Preference p = adapter.getItem(position);
        if (p == null || p instanceof PreferenceCategory) return;
        View v = holder.itemView;
        Resources res = v.getResources();
        int margin = res.getDimensionPixelSize(R.dimen.hg_settings_card_margin);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (p == mSections[HgSettingsSections.STATUS]) {
            v.setBackgroundResource(statusBackground(mStatusKind));
            lp.setMargins(margin, margin / 2, margin, 0);
            v.setLayoutParams(lp);
            return;
        }
        boolean above = position > 0 && joins(adapter.getItem(position - 1), p);
        boolean below = position + 1 < adapter.getItemCount()
                && joins(p, adapter.getItem(position + 1));
        float[] radii = HgSettingsCards.radii(above, below,
                res.getDimension(R.dimen.hg_settings_card_radius), 0f);
        GradientDrawable card = new GradientDrawable();
        card.setColor(v.getContext().getColor(R.color.hg_settings_card));
        card.setCornerRadii(radii);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(radii);
        int ripple = MaterialColors.getColor(v, androidx.appcompat.R.attr.colorControlHighlight);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), card, mask));
        lp.setMargins(margin, 0, margin, 0);
        v.setLayoutParams(lp);
    }

    /** Whether {@code row} continues the card of {@code above} (one section's rows). */
    private boolean joins(Preference above, Preference row) {
        if (above == null || row == null) return false;
        return HgSettingsCards.joins(!(above instanceof PreferenceCategory)
                && above != mSections[HgSettingsSections.STATUS],
                above.getParent(), row.getParent());
    }

    private static int statusBackground(int kind) {
        switch (kind) {
            case HgSettingsSections.STATUS_ON: return R.drawable.hg_status_on_bg;
            case HgSettingsSections.STATUS_STANDBY: return R.drawable.hg_status_standby_bg;
            default: return R.drawable.hg_status_off_bg;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        sShown = new WeakReference<>(this);
        updateSections();
        for (Runnable r : mRefreshers) r.run();
        updateSave();
    }

    /**
     * Shows the parts that work with this boot's state and the HOME holder (HgSettingsSections),
     * both looked up on each resume: the module's state changes as its setup and health check
     * go on, and the owner may have changed the home app meanwhile (no polling). The status is
     * read here, on the main thread, so the page never shows the wrong sections first: one
     * setting (cached by the system) and /proc's boot id, no dearer than the HOME lookup.
     */
    private void updateSections() {
        Context ctx = requireContext();
        String home = HgHome.packageName(ctx);
        HgModuleStatus status = HgModuleStatus.read(ctx);
        int mode = status == null ? HgSettingsSections.mode(null, home)
                : HgSettingsSections.mode(status.state, status.boot, home);
        for (int i = 0; i < mSections.length; i++) {
            if (mSections[i] != null) mSections[i].setVisible(HgSettingsSections.shows(i, mode));
        }
        Preference card = mSections[HgSettingsSections.STATUS];
        if (card != null) {
            // The kind first: the title's change rebinds the card with its background.
            mStatusKind = HgSettingsSections.statusKind(mode);
            switch (mStatusKind) {
                case HgSettingsSections.STATUS_ON:
                    card.setIcon(R.drawable.hg_ic_status_on);
                    card.setTitle(R.string.hg_settings_status_card_on);
                    break;
                case HgSettingsSections.STATUS_STANDBY:
                    card.setIcon(R.drawable.hg_ic_status_standby);
                    card.setTitle(R.string.hg_settings_status_card_standby);
                    break;
                default:
                    card.setIcon(R.drawable.hg_ic_status_off);
                    card.setTitle(R.string.hg_settings_status_card_off);
            }
            card.setSummary(noteText(mode, status));
        }
        refreshStatusList(mode, status);
    }

    /**
     * The status card's list (HgStatusCard), read on the helper thread (settings, /proc, the
     * overlay manager, the module's files) after any hide-line sync queued before it, then shown.
     */
    private void refreshStatusList(int mode, HgModuleStatus status) {
        HgStatusCardPreference card = mStatusCard;
        if (card == null) return;
        Context app = requireContext().getApplicationContext();
        long appCode = mAppCode;
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            List<HgStatusCardPreference.Item> items = statusItems(app, mode, status, appCode);
            Executors.MAIN_EXECUTOR.execute(() -> {
                if (isAdded()) card.setItems(items);
            });
        });
    }

    /** The status list's rows from this boot's runtime state (HgStatusCard); off main. */
    private static List<HgStatusCardPreference.Item> statusItems(Context app, int mode,
            HgModuleStatus status, long appCode) {
        ContentResolver cr = app.getContentResolver();
        String bootId = HgLogCollector.bootId();
        boolean system = (app.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean factsOn = HgRecentsSettings.isOn(global(cr, HgRecentsSettings.HEALTH_FACTS));
        boolean starting = status != null && HgModuleStatus.STARTING.equals(status.state);
        HgLogCollector.RunningModule running = HgLogCollector.runningModule(app);
        boolean moduleUpdate = HgLogCollector.moduleUpdate(app, running) != null;
        boolean appUpdate = (appCode >= 0 && HgModuleStatus.appBehind(appCode, running) != null)
                || (status != null && !status.staleApk.isEmpty());

        List<HgStatusCardPreference.Item> items = new ArrayList<>();
        item(items, app, R.string.hg_status_module, HgStatusCard.module(
                status == null ? null : status.state, status == null ? "" : status.health));
        item(items, app, R.string.hg_status_mount, HgStatusCard.mount(system,
                status == null ? "" : status.mount, status == null ? "" : status.mountMode));
        item(items, app, R.string.hg_status_recents, HgStatusCard.provider(
                factHolds(app, cr, HgHealthFacts.PROVIDER_BOUND, bootId), mode, starting,
                factsOn));
        item(items, app, R.string.hg_status_gestures, HgStatusCard.provider(
                factHolds(app, cr, HgHealthFacts.GESTURE_MONITOR, bootId), mode, starting,
                factsOn));
        item(items, app, R.string.hg_status_hooks, HgStatusCard.hooks(
                NotifKeys.hookedScopes(global(cr, NotifKeys.HOOKED_KEY), bootId),
                NotifKeys.isOn(global(cr, NotifKeys.KEY))));
        item(items, app, R.string.hg_status_reboot, HgStatusCard.reboot(
                HgStatusCard.rebootReasons(mode, HgHideGestureLine.status(app), moduleUpdate,
                        appUpdate)));
        if (status != null) {
            for (HgStatusCard.Row problem : HgStatusCard.problems(status.metamoduleMissed,
                    status.powerPending, status.restoreGaveUp)) {
                item(items, app, problemLabel(problem.value), problem);
            }
        }
        return items;
    }

    private static void item(List<HgStatusCardPreference.Item> items, Context app, int label,
            HgStatusCard.Row row) {
        items.add(new HgStatusCardPreference.Item(app.getString(label), valueText(app, row),
                row.level));
    }

    private static int problemLabel(int value) {
        switch (value) {
            case HgStatusCard.PROBLEM_METAMODULE: return R.string.hg_status_problem_metamodule;
            case HgStatusCard.PROBLEM_BATTERY: return R.string.hg_status_problem_battery;
            default: return R.string.hg_status_problem_restore;
        }
    }

    /** A row's value as the card shows it. */
    private static String valueText(Context app, HgStatusCard.Row row) {
        switch (row.value) {
            case HgStatusCard.STATE_ACTIVE:
                return app.getString(R.string.hg_status_state_active);
            case HgStatusCard.STATE_CHECKING:
                return app.getString(R.string.hg_status_state_checking);
            case HgStatusCard.STATE_HEALTH_FAILED:
                return app.getString(R.string.hg_status_state_health_failed);
            case HgStatusCard.STATE_STARTING:
                return app.getString(R.string.hg_status_state_starting);
            case HgStatusCard.STATE_STANDBY:
                return app.getString(R.string.hg_status_state_standby);
            case HgStatusCard.STATE_ROLLED_BACK:
                return app.getString(R.string.hg_status_state_rolled_back);
            case HgStatusCard.STATE_NOT_SET_UP:
                return app.getString(R.string.hg_status_state_not_set_up);
            case HgStatusCard.STATE_REFUSED:
                return app.getString(R.string.hg_status_state_refused);
            case HgStatusCard.STATE_NONE:
                return app.getString(R.string.hg_status_state_none);
            case HgStatusCard.STATE_OTHER:
                return row.detail;
            case HgStatusCard.MOUNTED:
                return app.getString(R.string.hg_status_mounted);
            case HgStatusCard.MOUNTED_VIA:
                return app.getString(R.string.hg_status_mounted_via, row.detail);
            case HgStatusCard.NOT_MOUNTED:
                return app.getString(R.string.hg_status_not_mounted);
            case HgStatusCard.PROBLEM_METAMODULE:
                return app.getString(R.string.hg_status_problem_metamodule_value, row.detail);
            case HgStatusCard.PROBLEM_BATTERY:
                return app.getString(R.string.hg_status_problem_battery_value);
            case HgStatusCard.PROBLEM_RESTORE:
                return app.getString(R.string.hg_status_problem_restore_value);
            case HgStatusCard.BY_US:
                return app.getString(R.string.hg_status_by_us);
            case HgStatusCard.BY_SYSTEM:
                return app.getString(R.string.hg_status_by_system);
            case HgStatusCard.HOOKS_LOADED: {
                List<String> names = new ArrayList<>();
                for (String scope : row.scopes) {
                    names.add(app.getString(NotifKeys.SCOPE_AOD.equals(scope)
                            ? R.string.hg_status_scope_aod : R.string.hg_status_scope_systemui));
                }
                return String.join(", ", names);
            }
            case HgStatusCard.HOOKS_NOT_LOADED:
                return app.getString(R.string.hg_status_hooks_not_loaded);
            case HgStatusCard.REBOOT_NOT_NEEDED:
                return app.getString(R.string.hg_status_reboot_not_needed);
            case HgStatusCard.REBOOT_PENDING: {
                List<String> names = new ArrayList<>();
                for (int reason : row.reasons) names.add(app.getString(reasonText(reason)));
                return app.getString(R.string.hg_status_reboot_pending,
                        String.join(", ", names));
            }
            default:
                return app.getString(R.string.hg_status_unknown);
        }
    }

    private static int reasonText(int reason) {
        switch (reason) {
            case HgStatusCard.REASON_HOME: return R.string.hg_status_reason_home;
            case HgStatusCard.REASON_NAV_LINE: return R.string.hg_status_reason_nav_line;
            case HgStatusCard.REASON_MODULE_UPDATE:
                return R.string.hg_status_reason_module_update;
            default: return R.string.hg_status_reason_app_update;
        }
    }

    /**
     * Whether our health fact {@code key} holds: this boot's value, its pid still our main
     * process (as the module's hg_app_fact counts it). The process is looked up with
     * ActivityManager, which lists our own app's processes: this page's process may not read
     * another one's /proc entry, so a /proc check said "System" while ours ran (0.3.9.52).
     */
    private static boolean factHolds(Context app, ContentResolver cr, String key, String bootId) {
        int pid = HgStatusCard.factPid(global(cr, key), bootId);
        return pid > 0 && HgStatusCard.isMainProcess(processName(app, pid),
                app.getPackageName());
    }

    /** The name of our own process {@code pid}; null if there is none. */
    private static String processName(Context app, int pid) {
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> list =
                    am == null ? null : am.getRunningAppProcesses();
            if (list == null) return null;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info.pid == pid) return info.processName;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "running processes unreadable: " + e);
        }
        return null;
    }

    private static String global(ContentResolver cr, String key) {
        try {
            return Settings.Global.getString(cr, key);
        } catch (RuntimeException e) {
            Log.w(TAG, key + " unreadable: " + e);
            return null;
        }
    }

    /** The status card's note for {@code mode}. */
    private String noteText(int mode, HgModuleStatus status) {
        switch (mode) {
            case HgSettingsSections.MODE_ACTIVE:
                return getString(R.string.hg_settings_status_card_on_note);
            case HgSettingsSections.MODE_ACTIVE_REBOOT:
                return getString(R.string.hg_settings_note_reboot_to_stock);
            case HgSettingsSections.MODE_STOCK_REBOOT:
                return getString(R.string.hg_settings_note_reboot_to_ours);
            case HgSettingsSections.MODE_OFF:
                if (HgModuleStatus.REFUSED.equals(status.state)) {
                    return getString(R.string.hg_settings_note_refused);
                }
                return getString(HgModuleStatus.ROLLED_BACK.equals(status.state)
                        ? R.string.hg_settings_note_rolled_back
                        : R.string.hg_settings_note_not_set_up, status.reason);
            default:
                return getString(R.string.hg_settings_system_home_note);
        }
    }

    private static PreferenceGroup category(PreferenceScreen screen, int title) {
        PreferenceCategory c = new PreferenceCategory(screen.getContext());
        c.setTitle(title);
        c.setIconSpaceReserved(false);
        screen.addPreference(c);
        return c;
    }

    private static Preference addInfo(PreferenceGroup group, int title, String summary) {
        Preference p = new Preference(group.getContext());
        p.setTitle(title);
        p.setSummary(summary);
        p.setSelectable(false);
        p.setIconSpaceReserved(false);
        group.addPreference(p);
        return p;
    }

    /** One of our Settings.Global off switches: on unless "0"; writes "1" or "0". */
    private void addGlobalSwitch(PreferenceGroup group, String key, int title, int summary) {
        ContentResolver cr = group.getContext().getContentResolver();
        addSwitch(group, title, summary,
                () -> HgRecentsSettings.isOn(Settings.Global.getString(cr, key)),
                on -> Settings.Global.putString(cr, key, on ? "1" : "0"));
    }

    private void addSwitch(PreferenceGroup group, int title, int summary, BooleanSupplier read,
            Predicate<Boolean> put) {
        addSwitch(group, title, summary, read, put, null);
    }

    /** As above; {@code shown} (if any) gets the switch's state on each resume and change. */
    private SwitchPreferenceCompat addSwitch(PreferenceGroup group, int title, int summary,
            BooleanSupplier read, Predicate<Boolean> put, Consumer<Boolean> shown) {
        SwitchPreferenceCompat p = new SwitchPreferenceCompat(group.getContext());
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        p.setIconSpaceReserved(false);
        p.setOnPreferenceChangeListener((pref, value) -> {
            if (!write(() -> put.test((Boolean) value))) return false;
            if (shown != null) shown.accept((Boolean) value);
            return true;
        });
        group.addPreference(p);
        mRefreshers.add(() -> {
            try {
                p.setChecked(read.getAsBoolean());
            } catch (RuntimeException e) {
                Log.w(TAG, "setting unreadable: " + e);
            }
            if (shown != null) shown.accept(p.isChecked());
        });
        return p;
    }

    /**
     * hypergestures_hide_gesture_line, the one switch that is off unless "1": writes "1" or "0",
     * then HgHideGestureLine.sync registers or removes the overlay on the helper thread (binder
     * calls), toasting if that failed. SystemUI draws the change after a reboot (the summary says
     * so). Under the summary, the overlay's state (HgHideGestureLine.status), read on the same
     * helper thread after any sync queued before it.
     */
    private Preference addHideLine(PreferenceGroup group) {
        Context app = group.getContext().getApplicationContext();
        ContentResolver cr = app.getContentResolver();
        String key = HgRecentsSettings.HIDE_GESTURE_LINE;
        Preference[] self = new Preference[1];
        self[0] = addSwitch(group, R.string.hg_settings_hide_line,
                R.string.hg_settings_hide_line_summary,
                () -> HgHideGestureLine.isOn(Settings.Global.getString(cr, key)),
                on -> {
                    if (!Settings.Global.putString(cr, key, on ? "1" : "0")) return false;
                    Executors.UI_HELPER_EXECUTOR.execute(() -> {
                        if (HgHideGestureLine.sync(app)) return;
                        Executors.MAIN_EXECUTOR.execute(() -> Toast.makeText(app,
                                R.string.hg_settings_hide_line_failed, Toast.LENGTH_LONG).show());
                    });
                    return true;
                },
                on -> Executors.UI_HELPER_EXECUTOR.execute(() -> {
                    int status = HgHideGestureLine.status(app);
                    Executors.MAIN_EXECUTOR.execute(() -> {
                        if (!isAdded() || self[0] == null) return;
                        String summary = getString(R.string.hg_settings_hide_line_summary);
                        int line = hideLineStatusText(status);
                        self[0].setSummary(line == 0 ? summary
                                : summary + "\n" + getString(line));
                    });
                }));
        return self[0];
    }

    /**
     * "Hide navigation line": the System launcher's own setting, Settings.Global
     * hide_gesture_line, written as MiuiHome writes it (putInt 1 or 0, nothing else; HgNavLine).
     * While it hides the line, Circle to Search shows off and is disabled (HyperOS starts none on
     * a hidden line, and our gesture service follows it) and our alternative is hidden. Both keep
     * their values meanwhile, so turning this off returns to what they say. With our gestures
     * SystemUI ignores hide_gesture_line (it applies it only in HyperOS's own gesture mode, which
     * we never turn on), so our overlay hides the line too (HgHideGestureLine.wanted); that shows
     * after a reboot.
     */
    private void addNavLineHide(PreferenceGroup group) {
        ContentResolver cr = group.getContext().getContentResolver();
        addSwitch(group, R.string.hg_settings_nav_line_hide,
                R.string.hg_settings_nav_line_hide_summary,
                () -> HgNavLine.hiddenNow(cr),
                on -> {
                    boolean ok = Settings.Global.putInt(cr, HgNavLine.HIDE_LINE, on ? 1 : 0);
                    HgHideGestureLine.syncLater(group.getContext());
                    return ok;
                },
                on -> {
                    updateCircleToSearch(on);
                    if (mAltHide != null) mAltHide.setVisible(HgNavLine.dependentsShown(on));
                });
    }

    /**
     * "Circle to Search" (owner, 2026-10-30, replacing the long press chooser): a long press on
     * the line starts it or does nothing, kept in the System launcher's own Settings.Secure
     * NavLongPress ("launch_google_search" or "none", written as MiuiHome's
     * NavShortcutConfigManager.writeSetting does), so its page and ours are one setting. Our
     * gesture service reads it at each touch-down (HgNavLine.longPressStartsSearch). While the
     * System launcher's hide is on it shows off and is disabled (updateCircleToSearch); an action
     * another app registered with MiuiHome shows off, and the summary says so.
     */
    private SwitchPreferenceCompat addCircleToSearch(PreferenceGroup group) {
        ContentResolver cr = group.getContext().getContentResolver();
        return addSwitch(group, R.string.hg_settings_cts, R.string.hg_settings_cts_summary,
                () -> HgNavLine.switchShownOn(HgNavLine.hiddenNow(cr), HgNavLine.actionNow(cr)),
                on -> Settings.Secure.putString(cr, HgNavLine.LONG_PRESS,
                        HgNavLine.valueForSwitch(on)),
                on -> updateCircleToSearch(HgNavLine.hiddenNow(cr)));
    }

    /**
     * Circle to Search for the System launcher's hide ({@code lineHidden}): disabled and shown
     * off while it hides the line, else what NavLongPress says; the summary to match.
     */
    private void updateCircleToSearch(boolean lineHidden) {
        SwitchPreferenceCompat p = mCircleToSearch;
        if (p == null) return;
        int action;
        try {
            action = HgNavLine.actionNow(p.getContext().getContentResolver());
        } catch (RuntimeException e) {
            Log.w(TAG, "long press setting unreadable: " + e);
            action = HgNavLine.ACTION_CTS;
        }
        p.setEnabled(HgNavLine.switchEnabled(lineHidden));
        p.setChecked(HgNavLine.switchShownOn(lineHidden, action));
        if (lineHidden) {
            p.setSummary(R.string.hg_settings_cts_summary_hidden);
        } else if (action == HgNavLine.ACTION_OTHER) {
            p.setSummary(getString(R.string.hg_settings_cts_summary) + "\n"
                    + getString(R.string.hg_settings_nav_long_press_other));
        } else {
            p.setSummary(R.string.hg_settings_cts_summary);
        }
    }

    /**
     * The line under "Hide navigation line (alternative)" for a HgHideGestureLine status; 0 for
     * none.
     */
    private static int hideLineStatusText(int status) {
        switch (status) {
            case HgHideGestureLine.STATUS_HIDDEN:
                return R.string.hg_settings_hide_line_hidden;
            case HgHideGestureLine.STATUS_HIDDEN_AFTER_REBOOT:
                return R.string.hg_settings_hide_line_hidden_after_reboot;
            case HgHideGestureLine.STATUS_SHOWN_AFTER_REBOOT:
                return R.string.hg_settings_hide_line_shown_after_reboot;
            case HgHideGestureLine.STATUS_NOT_APPLIED:
                return R.string.hg_settings_hide_line_not_applied;
            default:
                return 0;
        }
    }

    /**
     * hypergestures_notification_icons, off unless "1": writes "1" or "0". Our LSPosed hooks in
     * SystemUI and the AOD (their own APK and LSPosed module, com.hypergestures.notif) watch it
     * and apply a change to the next notification icon. They never run in our own process, so
     * whether LSPosed loaded them is told by SystemUI: it writes this boot's id to
     * hypergestures_notif_hooked once they are installed (NotifSetting.markHooked there). While
     * the switch is on the summary says whether that happened this boot.
     */
    private void addNotificationIcons(PreferenceGroup group) {
        ContentResolver cr = group.getContext().getContentResolver();
        String key = HgRecentsSettings.NOTIFICATION_ICONS;
        Preference[] self = new Preference[1];
        self[0] = addSwitch(group, R.string.hg_settings_notif_icons,
                R.string.hg_settings_notif_icons_summary,
                () -> NotifKeys.isOn(Settings.Global.getString(cr, key)),
                on -> Settings.Global.putString(cr, key, on ? "1" : "0"),
                on -> {
                    if (self[0] == null) return;
                    String summary = getString(R.string.hg_settings_notif_icons_summary);
                    if (on) {
                        summary += "\n" + getString(hooked(cr)
                                ? R.string.hg_settings_notif_active
                                : R.string.hg_settings_notif_not_loaded);
                    }
                    self[0].setSummary(summary);
                });
    }

    /**
     * hypergestures_app_info_fix, on unless "0": writes "1" or "0", then enables or disables
     * HgAppInfoTrampoline's component on the helper thread (a binder call), toasting if that
     * failed. Off, Android resolves App info as stock, without the hop.
     */
    private void addAppInfoFix(PreferenceGroup group) {
        Context app = group.getContext().getApplicationContext();
        ContentResolver cr = app.getContentResolver();
        String key = HgRecentsSettings.APP_INFO_FIX;
        addSwitch(group, R.string.hg_settings_app_info_fix,
                R.string.hg_settings_app_info_fix_summary,
                () -> HgRecentsSettings.isOn(Settings.Global.getString(cr, key)),
                on -> {
                    if (!Settings.Global.putString(cr, key, on ? "1" : "0")) return false;
                    Executors.UI_HELPER_EXECUTOR.execute(() -> {
                        if (HgAppInfoTrampoline.syncComponent(app)) return;
                        Executors.MAIN_EXECUTOR.execute(() -> Toast.makeText(app,
                                R.string.hg_settings_write_failed, Toast.LENGTH_SHORT).show());
                    });
                    return true;
                });
    }

    /** Whether SystemUI marked our notification hooks as loaded this boot (main thread: cheap). */
    private static boolean hooked(ContentResolver cr) {
        try {
            return NotifKeys.hookedThisBoot(
                    Settings.Global.getString(cr, NotifKeys.HOOKED_KEY),
                    HgLogCollector.bootId());
        } catch (RuntimeException e) {
            Log.w(TAG, "notification hook mark unreadable: " + e);
            return false;
        }
    }

    /**
     * hypergestures_recents_layout: vertical ("0"), horizontal ("1") or stack ("2"), in
     * MiuiHome's order, picked from three moving previews (HgLayoutPickerPreference). Unset is
     * filled by HgSettingsMigration, so there is no follow choice; until then (no permission)
     * the picker shows the layout recents follows from HyperOS.
     */
    private void addLayout(PreferenceGroup group) {
        Context ctx = group.getContext();
        ContentResolver cr = ctx.getContentResolver();
        HgLayoutPickerPreference p = new HgLayoutPickerPreference(ctx, choice -> write(
                () -> Settings.Global.putString(cr, HgRecentsSettings.RECENTS_LAYOUT, choice)));
        p.setTitle(R.string.hg_settings_layout);
        p.setSummary(R.string.hg_settings_layout_summary);
        group.addPreference(p);
        mRefreshers.add(() -> {
            String choice;
            try {
                // Not migrated yet (no permission): what recents shows, following HyperOS.
                choice = HgRecentsLayout.pageChoice(
                        Settings.Global.getString(cr, HgRecentsSettings.RECENTS_LAYOUT),
                        Settings.Global.getString(cr, HgRecentsLayout.MIUI_STYLE));
            } catch (RuntimeException e) {
                Log.w(TAG, "layout setting unreadable: " + e);
                choice = "";
            }
            p.setValue(choice);
        });
    }

    /**
     * A back sensitivity slider: Settings.Secure back_gesture_inset_scale_left/right on AOSP
     * Settings' five stops (HgBackPolicy.SENSITIVITY_STEPS, 0.6 to 1.66, the range back clamps
     * to); the next back swipe uses it (HgEdgeBack reads it on every edge touch-down).
     */
    private void addSensitivity(PreferenceGroup group, String key, int title) {
        ContentResolver cr = group.getContext().getContentResolver();
        SeekBarPreference p = new SeekBarPreference(group.getContext());
        p.setPersistent(false);
        p.setTitle(title);
        p.setIconSpaceReserved(false);
        p.setMin(0);
        p.setMax(HgBackPolicy.SENSITIVITY_STEPS.length - 1);
        p.setSeekBarIncrement(1);
        p.setShowSeekBarValue(false);
        p.setUpdatesContinuously(false);
        p.setOnPreferenceChangeListener((pref, value) -> {
            int step = (Integer) value;
            if (!write(() -> Settings.Secure.putFloat(cr, key,
                    HgBackPolicy.SENSITIVITY_STEPS[step]))) {
                return false;
            }
            p.setSummary(sensitivitySummary(step));
            return true;
        });
        group.addPreference(p);
        mRefreshers.add(() -> {
            float scale;
            try {
                scale = Settings.Secure.getFloat(cr, key, 1f);
            } catch (RuntimeException e) {
                Log.w(TAG, "back sensitivity unreadable: " + e);
                scale = 1f;
            }
            int step = HgBackPolicy.sensitivityStep(scale);
            p.setValue(step);
            p.setSummary(sensitivitySummary(step));
        });
    }

    private String sensitivitySummary(int step) {
        String scale = String.format(Locale.getDefault(), "%.2f",
                HgBackPolicy.SENSITIVITY_STEPS[step]);
        return getString(step == HgBackPolicy.DEFAULT_SENSITIVITY_STEP
                ? R.string.hg_settings_back_scale_default : R.string.hg_settings_back_scale, scale);
    }

    /** Opens {@code url} in the browser; a toast if there is none (or it refused). */
    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + url + ": " + e);
            Toast.makeText(requireContext(), R.string.hg_settings_no_browser,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Runs a settings write; false, with a toast, if it failed or threw. */
    private boolean write(BooleanSupplier put) {
        boolean ok;
        try {
            ok = put.getAsBoolean();
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot write the setting: " + e);
            ok = false;
        }
        if (!ok) {
            Toast.makeText(requireContext(), R.string.hg_settings_write_failed,
                    Toast.LENGTH_SHORT).show();
        }
        return ok;
    }

    private void updateSave() {
        if (mSave == null) return;
        mSave.setEnabled(!sSaving);
        mSave.setSummary(sSaving
                ? R.string.hg_settings_logs_saving : R.string.hg_settings_logs_save_summary);
    }

    /** Save logs: collected on a thread of its own (logcat and dumpsys take seconds). */
    private void startSave() {
        if (sSaving) return;
        sSaving = true;
        updateSave();
        boolean filtered = mFilter == null || mFilter.isChecked();
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String message = null;
            try {
                message = app.getString(R.string.hg_settings_logs_saved,
                        HgLogCollector.save(app, filtered));
            } catch (Throwable e) {
                // Anything at all (an Error too): the toast says why, the button comes back.
                Log.w(TAG, "save logs failed", e);
                String why = e.getMessage() != null ? e.getMessage() : e.toString();
                message = app.getString(R.string.hg_settings_logs_failed, why);
            } finally {
                String text = message;
                Executors.MAIN_EXECUTOR.execute(() -> {
                    sSaving = false;
                    if (text != null) Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                    HgSettingsFragment shown = sShown.get();
                    if (shown != null) shown.updateSave();
                });
            }
        }, "HgSaveLogs").start();
    }
}
