package com.saikiran.reminderssync;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * A small iCalendar reader/writer covering exactly the VTODO subset Apple
 * Reminders uses. A full library would drag in a lot of weight for properties
 * Reminders never writes.
 */
public class Ics {

    /** RFC 5545 folds long lines by starting the continuation with a space or tab. */
    private static String unfold(String raw) {
        return raw.replace("\r\n ", "").replace("\r\n\t", "")
                  .replace("\n ", "").replace("\n\t", "");
    }

    public static Task parse(String raw, String href, String etag, String listId, String listName) {
        Task t = new Task();
        t.href = href; t.etag = etag; t.listId = listId; t.listName = listName;
        boolean inTodo = false;

        for (String line : unfold(raw).split("\\r?\\n")) {
            if (line.startsWith("BEGIN:VTODO")) { inTodo = true; continue; }
            if (line.startsWith("END:VTODO")) break;
            if (!inTodo) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String name = head.contains(";") ? head.substring(0, head.indexOf(';')) : head;

            switch (name.toUpperCase(Locale.US)) {
                case "UID":         t.uid = value; break;
                case "SUMMARY":     t.title = unescape(value); break;
                case "DESCRIPTION": t.notes = unescape(value); break;
                case "PRIORITY":    t.priority = intOf(value); break;
                case "RELATED-TO":  t.parentUid = value; break;
                case "STATUS":      if ("COMPLETED".equalsIgnoreCase(value)) t.completed = true; break;
                case "COMPLETED":   t.completed = true; break;
                case "DUE": {
                    boolean dateOnly = head.toUpperCase(Locale.US).contains("VALUE=DATE")
                            && !head.toUpperCase(Locale.US).contains("DATE-TIME");
                    t.allDay = dateOnly || value.length() == 8;
                    t.due = parseDate(value, head);
                    break;
                }
            }
        }
        if (t.uid == null) t.uid = UUID.randomUUID().toString();
        return t;
    }

    private static int intOf(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Handles 20260416, 20260416T090000Z and 20260416T090000 with a TZID param. */
    private static long parseDate(String value, String head) {
        try {
            String v = value.trim();
            if (v.length() == 8) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                return f.parse(v).getTime();
            }
            boolean utc = v.endsWith("Z");
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            if (utc) {
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                v = v.substring(0, v.length() - 1);
            } else {
                String upper = head.toUpperCase(Locale.US);
                int i = upper.indexOf("TZID=");
                if (i >= 0) {
                    String tz = head.substring(i + 5);
                    int end = tz.indexOf(';');
                    if (end > 0) tz = tz.substring(0, end);
                    f.setTimeZone(TimeZone.getTimeZone(tz));
                } else {
                    f.setTimeZone(TimeZone.getDefault());
                }
            }
            return f.parse(v).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String write(Task t) {
        StringBuilder b = new StringBuilder();
        b.append("BEGIN:VCALENDAR\r\n");
        b.append("VERSION:2.0\r\n");
        b.append("PRODID:-//saikiran//RemindersSync//EN\r\n");
        b.append("BEGIN:VTODO\r\n");
        b.append("UID:").append(t.uid).append("\r\n");
        b.append("DTSTAMP:").append(utcStamp(System.currentTimeMillis())).append("\r\n");
        b.append(fold("SUMMARY:" + escape(t.title))).append("\r\n");
        if (t.notes != null && !t.notes.isEmpty()) {
            b.append(fold("DESCRIPTION:" + escape(t.notes))).append("\r\n");
        }
        if (t.due > 0) {
            if (t.allDay) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                b.append("DUE;VALUE=DATE:").append(f.format(new Date(t.due))).append("\r\n");
            } else {
                b.append("DUE:").append(utcStamp(t.due)).append("\r\n");
                // An alarm makes Apple's own clients notify too, not just this app.
                b.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\n")
                 .append("DESCRIPTION:").append(escape(t.title)).append("\r\n")
                 .append("TRIGGER;VALUE=DATE-TIME:").append(utcStamp(t.due)).append("\r\n")
                 .append("END:VALARM\r\n");
            }
        }
        if (t.priority > 0) b.append("PRIORITY:").append(t.priority).append("\r\n");
        if (t.parentUid != null) b.append("RELATED-TO:").append(t.parentUid).append("\r\n");
        if (t.completed) {
            b.append("STATUS:COMPLETED\r\n");
            b.append("PERCENT-COMPLETE:100\r\n");
            b.append("COMPLETED:").append(utcStamp(System.currentTimeMillis())).append("\r\n");
        } else {
            b.append("STATUS:NEEDS-ACTION\r\n");
        }
        b.append("END:VTODO\r\nEND:VCALENDAR\r\n");
        return b.toString();
    }

    private static String utcStamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    /** Servers may reject lines over 75 octets, so wrap with a leading space. */
    private static String fold(String line) {
        if (line.length() <= 73) return line;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int n = Math.min(73, line.length() - i);
            if (i > 0) b.append("\r\n ");
            b.append(line, i, i + n);
            i += n;
        }
        return b.toString();
    }

    public static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
