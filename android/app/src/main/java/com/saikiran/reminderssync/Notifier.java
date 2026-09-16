package com.saikiran.reminderssync;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/** Fires a local notification at each reminder's due time. */
public class Notifier {
    public static final String CHANNEL = "reminders";

    public static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Due reminders from iCloud");
        nm.createNotificationChannel(ch);
    }

    /** Re-derives every alarm from the current list, so completing one cancels it. */
    public static int schedule(Context c, List<Task> tasks) {
        ensureChannel(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long now = System.currentTimeMillis();
        int set = 0;

        for (Task t : tasks) {
            PendingIntent pi = intentFor(c, t);
            am.cancel(pi);
            // All-day reminders have no meaningful time; Apple shows them at 9am.
            if (t.completed || t.due == 0) continue;
            // A reminder whose moment has passed can't be scheduled; it just
            // shows as overdue in the list.
            long when = t.allDay ? Ics.startOfDay(t.due) + 9 * 3600_000L : t.due;
            if (when <= now) continue;

            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setWindow(AlarmManager.RTC_WAKEUP, when, 60_000, pi);
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                }
            } catch (SecurityException e) {
                am.setWindow(AlarmManager.RTC_WAKEUP, when, 60_000, pi);
            }
            set++;
        }
        return set;
    }

    /** Proves the whole path — AlarmManager, receiver, channel — in ten seconds. */
    public static void test(Context c) {
        ensureChannel(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, AlarmReceiver.class)
                .putExtra("title", "Test reminder")
                .putExtra("list", "If you see this, notifications work")
                .putExtra("uid", "test-alarm");
        PendingIntent pi = PendingIntent.getBroadcast(c, 987654, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + 10_000;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, when, 5_000, pi);
        }
    }

    private static PendingIntent intentFor(Context c, Task t) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .putExtra("title", t.title)
                .putExtra("list", t.listName)
                .putExtra("uid", t.uid);
        return PendingIntent.getBroadcast(c, t.uid.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void show(Context c, String title, String list, int id) {
        ensureChannel(c);
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(list)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }
}
