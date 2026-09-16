package com.saikiran.reminderssync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            // Alarms don't survive a reboot; rebuild them from the cache.
            Notifier.schedule(c, new Store(c).load());
            SyncJob.schedule(c);
            return;
        }
        String uid = i.getStringExtra("uid");
        Notifier.show(c, i.getStringExtra("title"), i.getStringExtra("list"),
                      uid == null ? 1 : uid.hashCode());
    }
}
