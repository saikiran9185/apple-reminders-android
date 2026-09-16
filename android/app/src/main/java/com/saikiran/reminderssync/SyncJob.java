package com.saikiran.reminderssync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Periodic pull. iCloud offers no push to third-party CalDAV clients — only
 * Apple's own apps get that — so polling is the only option available.
 */
public class SyncJob extends JobService {
    private static final int ID = 4711;

    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)     // the shortest interval Android honours
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                sync(getApplicationContext());
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    /** Shared by the job and the manual refresh in the UI. */
    public static List<Task> sync(Context c) throws Exception {
        Store store = new Store(c);
        if (!store.signedIn()) return new ArrayList<>();
        CalDav dav = new CalDav(store.user(), store.pass(), store.server());
        List<Task> all = new ArrayList<>();
        Exception last = null;
        int ok = 0;
        for (CalDav.Collection list : dav.lists()) {
            // iCloud's home holds collections that reject a VTODO query (inbox,
            // outbox, shared calendars). One refusal shouldn't lose the rest.
            try {
                all.addAll(dav.tasks(list));
                ok++;
            } catch (Exception e) {
                last = e;
            }
        }
        if (ok == 0 && last != null) throw last;
        store.save(all);
        Notifier.schedule(c, all);
        return all;
    }
}
