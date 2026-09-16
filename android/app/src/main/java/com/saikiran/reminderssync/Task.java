package com.saikiran.reminderssync;

/** One VTODO, flattened to what the UI and the notifier actually need. */
public class Task {
    public String uid;          // VTODO UID
    public String href;         // path of the .ics on the server
    public String etag;         // for If-Match on update/delete
    public String listId;       // collection href
    public String listName;
    public String title = "";
    public String notes;
    public long due = 0;        // epoch millis, 0 = none
    public boolean allDay;      // DUE;VALUE=DATE — no meaningful time
    public boolean completed;
    public int priority;        // 1-4 high, 5 normal, 6-9 low, 0 unset
    public String parentUid;    // RELATED-TO

    public boolean isOverdue() {
        if (completed || due == 0) return false;
        return due < System.currentTimeMillis();
    }
}
