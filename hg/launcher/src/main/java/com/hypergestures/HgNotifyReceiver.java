package com.hypergestures;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import com.android.quickstep.HgSettingsActivity;

/**
 * Posts the module's notices (rollback, "reboot to switch gestures", firmware) as this app, on its
 * own "HyperGestures" channel: HyperOS hides what `cmd notification post` posts, as that is
 * attributed to com.android.shell. The module's hg_notify sends
 * {@code am broadcast -a com.hypergestures.action.NOTIFY -n com.hypergestures/.HgNotifyReceiver
 * --es text TEXT [--es title TITLE] [--es tag TAG]}; a notice replaces the previous one with the
 * same tag. am's broadcast is ordered, so the result code tells the module whether a notification
 * was posted ({@link #RESULT_POSTED}); on anything else it falls back to `cmd notification post`.
 *
 * <p>The manifest protects it with android.permission.DUMP (signature|privileged|development):
 * root passes every permission check and the shell uid holds DUMP, ordinary apps can't send it.
 * Manifest-declared, so it costs nothing until a broadcast arrives (and R8 keeps it).
 */
public final class HgNotifyReceiver extends BroadcastReceiver {
    static final String ACTION = "com.hypergestures.action.NOTIFY";
    static final String CHANNEL_ID = "hypergestures";
    public static final String DEFAULT_TITLE = "HyperGestures";
    public static final String DEFAULT_TAG = "hypergestures";
    /** The result code for "posted"; am prints it as "Broadcast completed: result=1". */
    static final int RESULT_POSTED = 1;
    /** The notices' notification id (one per tag). */
    public static final int ID = 1;
    private static final String TAG = "HgNotifyReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String text = intent.getStringExtra("text");
        if (text == null || text.isEmpty()) return;
        String title = orDefault(intent.getStringExtra("title"), DEFAULT_TITLE);
        String tag = orDefault(intent.getStringExtra("tag"), DEFAULT_TAG);
        if (post(context, title, text, tag) && isOrderedBroadcast()) setResultCode(RESULT_POSTED);
    }

    /**
     * Posts {@code text} as notification {@code tag} on the "HyperGestures" channel (replacing one
     * with the same tag) and toasts it. Also used by the app itself (HgHomeSwitch), so its notices
     * and the module's look the same and replace each other. Main thread (the toast). Never
     * throws; false if nothing was posted.
     */
    public static boolean post(Context context, String title, String text, String tag) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        try {
            // Idempotent; never overrides what the owner set for the channel.
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, DEFAULT_TITLE, NotificationManager.IMPORTANCE_DEFAULT));
            // Not allowed (no POST_NOTIFICATIONS, app or channel blocked): say so, so the module
            // falls back instead of believing a notification nobody sees was shown.
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (!nm.areNotificationsEnabled() || channel == null
                    || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "notifications are off for this app or its channel: not posted");
                return false;
            }
            // A framework icon, not one of ours: KernelSU hides module mounts from SystemUI too, so
            // SystemUI can't open our APK, fails to inflate the notification and drops it
            // ("Unable to open /system/priv-app/HyperGestures/HyperGestures.apk").
            Notification n = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(Icon.createWithResource("android",
                            android.R.drawable.ic_dialog_info))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(settingsIntent(context))
                    .setAutoCancel(true)
                    .build();
            nm.notify(tag, ID, n);
            // Also a toast, so a notice is seen even if HyperOS folds the notification away.
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "not posted", e);
            return false;
        }
        return true;
    }

    /**
     * A tap opens the settings page (its status card says what the notice is about), and
     * auto-cancel then clears the notice. A PendingIntent, unlike the icon, needs nothing from our
     * APK in SystemUI: the system starts the activity as us.
     */
    private static PendingIntent settingsIntent(Context context) {
        Intent open = new Intent(context, HgSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Removes notice {@code tag} if it is still shown with exactly {@code text} (it no longer
     * applies); another notice under the same tag (the module posts all its notices as
     * "hypergestures": a rollback, a crash) stays. Never throws.
     */
    public static void cancel(Context context, String tag, String text) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                CharSequence shown = sbn.getNotification().extras
                        .getCharSequence(Notification.EXTRA_TEXT);
                if (isNotice(sbn.getTag(), sbn.getId(), shown, tag, text)) {
                    nm.cancel(tag, ID);
                    return;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "not cancelled", e);
        }
    }

    /** Whether a shown notification ({@code tag}, {@code id}, {@code shown} text) is our notice. */
    public static boolean isNotice(String tag, int id, CharSequence shown, String wantTag,
            String wantText) {
        return id == ID && wantTag != null && wantTag.equals(tag) && shown != null
                && wantText != null && wantText.contentEquals(shown);
    }

    static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
