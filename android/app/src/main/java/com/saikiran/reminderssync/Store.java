package com.saikiran.reminderssync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Credentials and the last good snapshot, so the app opens instantly offline. */
public class Store {
    private static final String FILE = "reminders";
    private final SharedPreferences p;

    public Store(Context c) { p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    public String user() { return p.getString("user", null); }
    public String pass() { return p.getString("pass", null); }
    public String server() { return p.getString("server", CalDav.ROOT); }
    public boolean signedIn() { return user() != null && pass() != null; }

    public void signIn(String user, String pass, String server) {
        p.edit().putString("user", user).putString("pass", pass)
                .putString("server", server).apply();
    }

    public void signOut() { p.edit().clear().apply(); }

    public long lastSync() { return p.getLong("lastSync", 0); }

    public void save(List<Task> tasks) {
        JSONArray arr = new JSONArray();
        for (Task t : tasks) {
            try {
                JSONObject o = new JSONObject();
                o.put("uid", t.uid); o.put("href", t.href); o.put("etag", t.etag);
                o.put("listId", t.listId); o.put("listName", t.listName);
                o.put("title", t.title); o.put("notes", t.notes == null ? "" : t.notes);
                o.put("due", t.due); o.put("allDay", t.allDay);
                o.put("completed", t.completed); o.put("priority", t.priority);
                o.put("parentUid", t.parentUid == null ? "" : t.parentUid);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        p.edit().putString("tasks", arr.toString())
                .putLong("lastSync", System.currentTimeMillis()).apply();
    }

    public List<Task> load() {
        List<Task> out = new ArrayList<>();
        String raw = p.getString("tasks", null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.uid = o.optString("uid"); t.href = o.optString("href", null);
                t.etag = o.optString("etag", null);
                t.listId = o.optString("listId"); t.listName = o.optString("listName");
                t.title = o.optString("title"); t.notes = o.optString("notes");
                t.due = o.optLong("due"); t.allDay = o.optBoolean("allDay");
                t.completed = o.optBoolean("completed"); t.priority = o.optInt("priority");
                String parent = o.optString("parentUid");
                t.parentUid = parent.isEmpty() ? null : parent;
                out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
