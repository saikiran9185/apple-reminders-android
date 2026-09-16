package com.saikiran.reminderssync;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Just enough CalDAV for Apple Reminders.
 *
 * iCloud has no public API for Reminders, but it does expose them as CalDAV
 * VTODO collections. Apple doesn't document this for third parties; it has been
 * stable for years, and an app-specific password is what gets you in — the
 * account password is refused outright once two-factor auth is on.
 */
public class CalDav {
    public static final String ROOT = "https://caldav.icloud.com";
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType ICAL = MediaType.parse("text/calendar; charset=utf-8");

    public static class Collection {
        public String href, name;
        public Collection(String href, String name) { this.href = href; this.name = name; }
    }

    private final OkHttpClient client;
    private final String auth;

    private final String root;

    public CalDav(String user, String appPassword) { this(user, appPassword, ROOT); }

    public CalDav(String user, String appPassword, String server) {
        this.root = (server == null || server.trim().isEmpty()) ? ROOT : server.trim();
        this.auth = Credentials.basic(user, appPassword);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                // Redirects are followed by hand below. OkHttp keeps the method
                // for PROPFIND but turns a redirected REPORT into a GET, which
                // iCloud answers with 403 — the request never arrives as a REPORT.
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    // MARK: - Discovery

    /** Root → principal → calendar-home-set → the collections that hold VTODOs. */
    public List<Collection> lists() throws IOException {
        String base = root.endsWith("/") ? root : root + "/";
        String principal = firstHref(propfind(base, 0,
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>"),
                "current-user-principal");
        if (principal == null) {
            throw new IOException("No principal at " + root
                    + " — check the server address and the username.");
        }

        String home = firstHref(propfind(resolve(base, principal), 0,
                "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                        + "<d:prop><c:calendar-home-set/></d:prop></d:propfind>"),
                "calendar-home-set");
        if (home == null) throw new IOException("Could not find your calendar home.");

        String homeUrl = resolve(base, home);
        String body = propfind(homeUrl, 1,
                "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                        + "<d:prop><d:displayname/><d:resourcetype/>"
                        + "<c:supported-calendar-component-set/></d:prop></d:propfind>");

        List<Collection> out = new ArrayList<>();
        for (Map<String, String> r : responses(body)) {
            // Reminders live in collections that accept VTODO; calendars accept VEVENT.
            if (!"1".equals(r.get("vtodo"))) continue;
            String href = r.get("href");
            if (href == null) continue;
            String name = r.get("displayname");
            out.add(new Collection(resolve(homeUrl, href),
                                   name == null || name.isEmpty() ? "Reminders" : name));
        }
        return out;
    }

    // MARK: - Reading

    public List<Task> tasks(Collection c) throws IOException {
        String body = report(c.href,
                "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                        + "<d:prop><d:getetag/><c:calendar-data/></d:prop>"
                        + "<c:filter><c:comp-filter name=\"VCALENDAR\">"
                        + "<c:comp-filter name=\"VTODO\"/></c:comp-filter></c:filter>"
                        + "</c:calendar-query>");

        List<Task> out = new ArrayList<>();
        for (Map<String, String> r : responses(body)) {
            String data = r.get("calendar-data");
            if (data == null || !data.contains("VTODO")) continue;
            out.add(Ics.parse(data, resolve(c.href, r.get("href")),
                              r.get("getetag"), c.href, c.name));
        }
        return out;
    }

    // MARK: - Writing

    /** Creates or replaces the .ics for this task. */
    public void save(Task t) throws IOException {
        boolean isNew = t.href == null;
        if (isNew) {
            if (t.uid == null) t.uid = UUID.randomUUID().toString().toUpperCase();
            t.href = t.listId + (t.listId.endsWith("/") ? "" : "/") + t.uid + ".ics";
        }
        Request.Builder b = new Request.Builder()
                .url(t.href)
                .header("Authorization", auth)
                .put(RequestBody.create(Ics.write(t), ICAL));
        if (isNew) b.header("If-None-Match", "*");
        else if (t.etag != null) b.header("If-Match", t.etag);

        try (Response res = follow(b, t.href)) {
            // A 412 means the server copy moved on; the next sync will reconcile.
            if (!res.isSuccessful() && res.code() != 412) {
                throw new IOException("Save failed: HTTP " + res.code());
            }
            String etag = res.header("ETag");
            if (etag != null) t.etag = etag;
        }
    }

    public void delete(Task t) throws IOException {
        if (t.href == null) return;
        Request.Builder b = new Request.Builder()
                .url(t.href).header("Authorization", auth).delete();
        if (t.etag != null) b.header("If-Match", t.etag);
        try (Response res = follow(b, t.href)) {
            if (!res.isSuccessful() && res.code() != 404) {
                throw new IOException("Delete failed: HTTP " + res.code());
            }
        }
    }

    // MARK: - Plumbing

    /** Replays a request against redirects without losing its method or body. */
    private Response follow(Request.Builder builder, String url) throws IOException {
        String target = url;
        Response res = null;
        for (int hop = 0; hop < 5; hop++) {
            if (res != null) res.close();
            res = client.newCall(builder.url(target).build()).execute();
            String location = res.header("Location");
            if (!isRedirect(res.code()) || location == null) return res;
            target = resolve(target, location);
        }
        return res;
    }

    private String propfind(String url, int depth, String body) throws IOException {
        return dav("PROPFIND", url, String.valueOf(depth), body);
    }

    private String report(String url, String body) throws IOException {
        return dav("REPORT", url, "1", body);
    }

    private String dav(String method, String url, String depth, String body) throws IOException {
        String target = url;
        for (int hop = 0; hop < 5; hop++) {
            Request req = new Request.Builder()
                    .url(target)
                    .header("Authorization", auth)
                    .header("Depth", depth)
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .method(method, RequestBody.create(body, XML))
                    .build();
            try (Response res = client.newCall(req).execute()) {
                String location = res.header("Location");
                if (isRedirect(res.code()) && location != null) {
                    target = resolve(target, location);   // same method, same body
                    continue;
                }
                String text = res.body() == null ? "" : res.body().string();
                if (res.code() == 401) {
                    throw new IOException(root.contains("icloud")
                            ? "iCloud rejected the sign-in. Use an app-specific password from "
                              + "appleid.apple.com, not your Apple ID password."
                            : "Server rejected the username or password.");
                }
                if (res.code() != 207 && !res.isSuccessful()) {
                    throw new IOException("HTTP " + res.code() + " on " + method + " "
                            + shortPath(target));
                }
                return text;
            }
        }
        throw new IOException("Too many redirects from iCloud");
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Keeps error messages readable without dumping the whole account URL. */
    private static String shortPath(String url) {
        HttpUrl u = HttpUrl.parse(url);
        if (u == null) return url;
        List<String> seg = u.pathSegments();
        return seg.isEmpty() ? "/" : "…/" + seg.get(seg.size() - 1);
    }

    private static String resolve(String base, String href) {
        if (href == null) return null;
        if (href.startsWith("http")) return href;
        HttpUrl b = HttpUrl.parse(base);
        if (b == null) return href;
        HttpUrl r = b.resolve(href);
        return r == null ? href : r.toString();
    }

    /**
     * Pulls the first href nested inside the named element. DAV puts the answer
     * in a child href rather than as text on the property itself.
     */
    private static String firstHref(String xml, String parent) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            boolean inside = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    if (local(p.getName()).equals(parent)) inside = true;
                    else if (inside && local(p.getName()).equals("href")) return p.nextText().trim();
                } else if (e == XmlPullParser.END_TAG && local(p.getName()).equals(parent)) {
                    inside = false;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Flattens a 207 Multi-Status into one map per &lt;response&gt;. */
    private static List<Map<String, String>> responses(String xml) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            Map<String, String> current = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if (name.equals("response")) {
                        current = new HashMap<>();
                    } else if (current != null) {
                        switch (name) {
                            case "href":
                                if (!current.containsKey("href")) current.put("href", p.nextText().trim());
                                break;
                            case "displayname":   current.put("displayname", safeText(p)); break;
                            case "getetag":       current.put("getetag", safeText(p)); break;
                            case "calendar-data": current.put("calendar-data", safeText(p)); break;
                            case "comp":
                                if ("VTODO".equalsIgnoreCase(p.getAttributeValue(null, "name"))) {
                                    current.put("vtodo", "1");
                                }
                                break;
                        }
                    }
                } else if (e == XmlPullParser.END_TAG
                        && local(p.getName()).equals("response") && current != null) {
                    out.add(current);
                    current = null;
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String safeText(XmlPullParser p) {
        try { return p.nextText(); } catch (Exception e) { return ""; }
    }

    /** Strips the namespace prefix so DAV:, caldav: and Apple's own all match. */
    private static String local(String name) {
        int i = name.indexOf(':');
        return (i >= 0 ? name.substring(i + 1) : name).toLowerCase();
    }
}
