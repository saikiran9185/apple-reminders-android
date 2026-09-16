package com.saikiran.reminderssync;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    // Apple's dark system palette, so this doesn't look like a generic to-do app.
    static final int BG = Color.parseColor("#000000");
    static final int CARD = Color.parseColor("#1C1C1E");
    static final int TEXT = Color.parseColor("#FFFFFF");
    static final int DIM = Color.parseColor("#8E8E93");
    static final int BLUE = Color.parseColor("#0A84FF");
    static final int RED = Color.parseColor("#FF453A");

    private Store store;
    private List<Task> tasks = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private boolean showCompleted = false;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        store = new Store(this);
        Notifier.ensureChannel(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (store.signedIn()) showList(); else showLogin();
    }

    @Override protected void onResume() {
        super.onResume();
        // Coming back to the app should show current data without having to
        // reach for the Sync button every time.
        if (store.signedIn() && content != null) sync(false);
    }

    // MARK: - Sign in

    private void showLogin() {
        LinearLayout root = column(24);
        root.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(label("Reminders", 32, TEXT, true));
        root.addView(label("Syncs tasks over CalDAV, so it works on mobile data "
                + "with your Mac switched off.", 13, DIM, false));
        root.addView(gap(20));

        EditText server = field("CalDAV server", InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(CalDav.ROOT);
        root.addView(server);
        root.addView(gap(10));

        EditText user = field("Username / Apple ID", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(user);
        root.addView(gap(10));
        root.addView(pass);
        root.addView(gap(6));
        root.addView(label("For iCloud use an app-specific password from appleid.apple.com "
                + "— two-factor blocks the real one. Any other CalDAV server "
                + "(Nextcloud, Fastmail, Radicale) works too: paste its address above.",
                11, DIM, false));
        root.addView(gap(18));

        Button go = new Button(this);
        go.setText("Sign in");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setBackground(rounded(BLUE, 12));
        root.addView(go);

        status = label("", 12, DIM, false);
        root.addView(gap(12));
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);

        go.setOnClickListener(v -> {
            String u = user.getText().toString().trim();
            String p = pass.getText().toString().trim().replace(" ", "");
            String typed = server.getText().toString().trim();
            if (u.isEmpty() || p.isEmpty()) { status.setText("Fill in both fields."); return; }
            final String host = typed.startsWith("http") ? typed : "https://" + typed;
            status.setText("Connecting to iCloud…");
            go.setEnabled(false);
            new Thread(() -> {
                try {
                    // Prove the credentials work before storing them.
                    new CalDav(u, p, host).lists();
                    store.signIn(u, p, host);
                    SyncJob.schedule(this);
                    runOnUiThread(this::showList);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        status.setText(e.getMessage() == null ? "Sign-in failed." : e.getMessage());
                        go.setEnabled(true);
                    });
                }
            }).start();
        });
    }

    // MARK: - List

    private void showList() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(18), dp(10), dp(4));

        TextView title = label("Reminders", 30, TEXT, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        bar.addView(iconButton("\u21BB", "Sync", v -> sync(true)));
        bar.addView(iconButton("+", "New reminder", v -> addDialog()));
        bar.addView(iconButton("\u22EF", "More", v -> menu()));

        root.addView(bar);

        status = label("", 12, DIM, false);
        status.setPadding(dp(21), 0, dp(20), dp(10));
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(40));
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sv);

        setContentView(root);

        tasks = store.load();
        render();
        sync(false);
    }

    /** Square, finger-sized, and visually quiet until touched. */
    private TextView iconButton(String glyph, String description, View.OnClickListener action) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTextColor(BLUE);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        b.setLayoutParams(lp);
        b.setOnClickListener(action);
        b.setBackground(rounded(Color.TRANSPARENT, 22));
        b.setClickable(true);
        return b;
    }

    private void menu() {
        String toggle = showCompleted ? "Hide completed" : "Show completed";
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{toggle, "Test notification", "Sign out"},
                        (d, which) -> {
                            if (which == 0) { showCompleted = !showCompleted; render(); }
                            else if (which == 1) {
                                Notifier.test(this);
                                toast("A test notification will arrive in 10 seconds. "
                                        + "If it doesn't, allow Autostart for this app "
                                        + "in MIUI settings.");
                            } else {
                                store.signOut();
                                recreate();
                            }
                        })
                .show();
    }

    private void sync(boolean loud) {
        if (loud) status.setText("Syncing…");
        new Thread(() -> {
            try {
                List<Task> fresh = SyncJob.sync(this);
                runOnUiThread(() -> {
                    tasks = fresh;
                    int set = Notifier.schedule(this, fresh);
                    status.setTextColor(DIM);
                    status.setText("Updated just now \u00B7 " + (set == 0
                            ? "nothing scheduled"
                            : set + " reminder" + (set == 1 ? "" : "s") + " scheduled"));
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setTextColor(RED);
                    status.setText(e.getMessage() == null ? "Sync failed" : e.getMessage());
                });
            }
        }).start();
    }

    /** Groups by list, then by urgency inside it — the shape Reminders uses. */
    private void render() {
        content.removeAllViews();
        Map<String, List<Task>> byList = new LinkedHashMap<>();
        int done = 0;
        for (Task t : tasks) {
            if (t.completed) { done++; if (!showCompleted) continue; }
            byList.computeIfAbsent(t.listName == null ? "Reminders" : t.listName,
                                   k -> new ArrayList<>()).add(t);
        }

        if (byList.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(6), dp(90), dp(6), dp(6));
            TextView mark = label("\u25CB", 44, Color.parseColor("#2C2C2E"), false);
            mark.setGravity(Gravity.CENTER);
            empty.addView(mark);
            empty.addView(gap(10));
            TextView t1 = label("No reminders", 17, TEXT, true);
            t1.setGravity(Gravity.CENTER);
            empty.addView(t1);
            empty.addView(gap(4));
            TextView t2 = label(done > 0 ? done + " completed" : "Add one with +", 13, DIM, false);
            t2.setGravity(Gravity.CENTER);
            empty.addView(t2);
            content.addView(empty);
            return;
        }

        for (Map.Entry<String, List<Task>> e : byList.entrySet()) {
            TextView header = label(e.getKey(), 13, DIM, true);
            header.setPadding(dp(6), dp(20), dp(6), dp(8));
            content.addView(header);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rounded(CARD, 14));

            List<Task> items = e.getValue();
            Collections.sort(items, Comparator.comparingLong(
                    t -> t.completed ? Long.MAX_VALUE : (t.due == 0 ? Long.MAX_VALUE - 1 : t.due)));
            for (int i = 0; i < items.size(); i++) {
                card.addView(row(items.get(i)));
                if (i < items.size() - 1) card.addView(divider());
            }
            content.addView(card);
        }

        if (done > 0 && !showCompleted) {
            TextView more = label(done + " completed \u00B7 tap to show", 12, DIM, false);
            more.setPadding(dp(6), dp(18), dp(6), dp(6));
            more.setOnClickListener(v -> { showCompleted = true; render(); });
            content.addView(more);
        }
    }

    /** Inset hairline, so rows read as one card rather than separate blocks. */
    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(50);
        v.setLayoutParams(lp);
        v.setBackgroundColor(Color.parseColor("#2C2C2E"));
        return v;
    }

    /** A drawn ring, rather than the "○" character, which sits off-centre. */
    private View checkCircle(Task task) {
        TextView c = new TextView(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (task.completed) {
            g.setColor(BLUE);
            c.setText("\u2713");
            c.setTextColor(Color.WHITE);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), task.isOverdue() ? RED : Color.parseColor("#48484A"));
        }
        c.setBackground(g);
        c.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lp.rightMargin = dp(14);
        lp.topMargin = dp(2);
        c.setLayoutParams(lp);
        return c;
    }

    private View row(Task task) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(14), dp(13), dp(14), dp(13));
        View circle = checkCircle(task);
        // The circle completes; the row opens the editor. Tapping a row to
        // silently tick it off meant there was no way to change anything.
        circle.setOnClickListener(v -> toggle(task));
        r.addView(circle);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = label(task.title, 16, task.completed ? DIM : TEXT, false);
        if (task.completed) {
            t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        col.addView(t);

        if (task.notes != null && !task.notes.trim().isEmpty()) {
            TextView n = label(task.notes.trim(), 13, DIM, false);
            n.setMaxLines(2);
            col.addView(n);
        }
        if (task.due > 0 && !task.completed) {
            TextView d = label(dueLabel(task), 13, task.isOverdue() ? RED : DIM, false);
            d.setPadding(0, dp(2), 0, 0);
            col.addView(d);
        }
        r.addView(col);

        r.setOnClickListener(v -> editDialog(task));
        r.setOnLongClickListener(v -> { confirmDelete(task); return true; });
        return r;
    }

    private String dueLabel(Task t) {
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(t.due);
        long days = (Ics.startOfDay(t.due) - Ics.startOfDay(System.currentTimeMillis()))
                / 86_400_000L;
        String day = days == 0 ? "Today" : days == 1 ? "Tomorrow" : days == -1 ? "Yesterday"
                : DateFormat.format("E d MMM", due).toString();
        if (t.allDay) return day;
        return day + ", " + DateFormat.getTimeFormat(this).format(due.getTime());
    }

    private void toggle(Task task) {
        task.completed = !task.completed;
        render();
        new Thread(() -> {
            try {
                new CalDav(store.user(), store.pass()).save(task);
                runOnUiThread(() -> { store.save(tasks); Notifier.schedule(this, tasks); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    task.completed = !task.completed;   // put it back
                    render();
                    toast("Couldn't update: " + e.getMessage());
                });
            }
        }).start();
    }

    private void confirmDelete(Task task) {
        new AlertDialog.Builder(this)
                .setTitle("Delete reminder?")
                .setMessage(task.title)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    try {
                        new CalDav(store.user(), store.pass(), store.server()).delete(task);
                        runOnUiThread(() -> { tasks.remove(task); store.save(tasks); render(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("Couldn't delete: " + e.getMessage()));
                    }
                }).start())
                .show();
    }

    private void addDialog() { editDialog(null); }

    /**
     * One editor for both new and existing reminders — the app could previously
     * only create, never change, anything.
     */
    private void editDialog(final Task existing) {
        final boolean isNew = existing == null;
        if (isNew && tasks.isEmpty() && store.lastSync() == 0) { toast("Sync first."); return; }

        final Task t = isNew ? new Task() : existing;

        LinearLayout box = column(18);

        final EditText title = field("Title", InputType.TYPE_CLASS_TEXT);
        title.setText(t.title);
        box.addView(title);
        box.addView(gap(10));

        final EditText notes = field("Notes", InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setText(t.notes == null ? "" : t.notes);
        notes.setMinLines(2);
        box.addView(notes);
        box.addView(gap(14));

        // The picked moment, seeded from the existing due date.
        final Calendar cal = Calendar.getInstance();
        if (t.due > 0) cal.setTimeInMillis(t.due);
        else { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0); }
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        final Switch hasDate = toggleRow(box, "Date", t.due > 0);
        final Button dateBtn = flatButton(DateFormat.format("E d MMM yyyy", cal).toString(), BLUE);
        dateBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        box.addView(dateBtn);

        final Switch hasTime = toggleRow(box, "Time", t.due > 0 && !t.allDay);
        final Button timeBtn = flatButton(DateFormat.getTimeFormat(this)
                .format(cal.getTime()), BLUE);
        timeBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        box.addView(timeBtn);

        dateBtn.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, m, d) -> {
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            hasDate.setChecked(true);
            dateBtn.setText(DateFormat.format("E d MMM yyyy", cal));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show());

        timeBtn.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, min) -> {
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min);
            hasDate.setChecked(true);
            hasTime.setChecked(true);
            timeBtn.setText(DateFormat.getTimeFormat(this).format(cal.getTime()));
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this)).show());

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "New reminder" : "Edit reminder")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String text = title.getText().toString().trim();
                    if (text.isEmpty()) { toast("Give it a title."); return; }

                    String before = t.title;
                    long beforeDue = t.due;
                    t.title = text;
                    String n = notes.getText().toString().trim();
                    t.notes = n.isEmpty() ? null : n;
                    if (hasDate.isChecked()) {
                        t.allDay = !hasTime.isChecked();
                        if (t.allDay) {
                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
                        }
                        t.due = cal.getTimeInMillis();
                    } else {
                        t.due = 0;
                        t.allDay = false;
                    }

                    if (isNew) {
                        if (tasks.isEmpty()) { toast("Sync first so the app knows your lists."); return; }
                        t.listId = tasks.get(0).listId;
                        t.listName = tasks.get(0).listName;
                        tasks.add(t);
                    }
                    render();
                    push(t, isNew, before, beforeDue);
                });

        if (!isNew) b.setNeutralButton("Delete", (d, w) -> confirmDelete(t));
        b.show();
    }

    /** A labelled switch on one line, since AlertDialog has no form layout. */
    private Switch toggleRow(LinearLayout parent, String text, boolean on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = label(text, 15, TEXT, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        Switch sw = new Switch(this);
        sw.setChecked(on);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    /** Sends a change upstream, restoring the old values if the server says no. */
    private void push(Task t, boolean isNew, String oldTitle, long oldDue) {
        new Thread(() -> {
            try {
                new CalDav(store.user(), store.pass(), store.server()).save(t);
                runOnUiThread(() -> {
                    store.save(tasks);
                    int set = Notifier.schedule(this, tasks);
                    status.setTextColor(DIM);
                    status.setText(set == 0 ? "Saved \u00B7 no future reminders set"
                                            : "Saved \u00B7 " + set + " reminder"
                                              + (set == 1 ? "" : "s") + " scheduled");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isNew) tasks.remove(t);
                    else { t.title = oldTitle; t.due = oldDue; }
                    render();
                    toast("Couldn't save: " + e.getMessage());
                });
            }
        }).start();
    }

    // MARK: - Small view helpers

    private LinearLayout column(int pad) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        l.setBackgroundColor(BG);
        return l;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | type);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setBackground(rounded(CARD, 10));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private Button flatButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
