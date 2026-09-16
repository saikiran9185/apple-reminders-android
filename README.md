# Apple Reminders on Android

Two halves of one system that puts **Apple Reminders on an Android phone**, with
two-way sync, due times and notifications.

- **`android/`** — a native Android CalDAV client (plain Java, no AndroidX).
- **`mac/`** — a menu bar agent that bridges Reminders.app to iCloud.

## Why this exists

Apple has no public API for Reminders, and no Android app. iCloud *does* expose
reminders as CalDAV `VTODO` collections, so a CalDAV client should be enough —
and for some accounts it is.

It isn't if your account has **upgraded reminders** (the iOS 13+ format). An
upgraded account answers a CalDAV query with two placeholder tasks:

> The creator of this list has upgraded these reminders.
> Where are my reminders?

Your actual reminders are served over a private protocol instead. Nothing
third-party — this app, Tasks.org, DAVx5 — can reach them, and there is no way
to downgrade. Writes still land in that legacy collection, but Reminders.app
never reads them, so both directions are dead.

**The way around it:** EventKit on a Mac *can* see upgraded reminders. So the
Mac agent mirrors Reminders.app into that otherwise-abandoned CalDAV collection,
and the phone reads it from anywhere, over mobile data, with the Mac asleep.

```
Reminders.app ──EventKit── Mac agent ──CalDAV── iCloud ──CalDAV── Android app
```

If your reminders are *not* upgraded, or you use another CalDAV server
(Nextcloud, Fastmail, Radicale), skip the Mac agent — the Android app talks to
any CalDAV server directly.

## Setup

**Both halves need an app-specific password** from appleid.apple.com →
Sign-In and Security → App-Specific Passwords. Two-factor auth rejects the real
Apple ID password.

### Mac agent
```sh
cd mac && ./install.sh
```
Then click the checklist icon in the menu bar, enter your Apple ID and the
app-specific password, and turn on **Start at login**. macOS will ask for
Reminders access.

It syncs the moment Reminders.app changes, and every 30s for the phone's side.
Log: `~/Library/Application Support/RemindersBridge/log.txt` (`↑pushed ↓pulled ✕deleted`).

### Android app
```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Sign in with the CalDAV server (`https://caldav.icloud.com` by default), your
Apple ID, and the app-specific password.

## What works

Two-way sync of title, notes, due date **and time**, completion and deletion.
Local notifications at each due time. Offline cache. Background sync every 15
minutes (Android's floor) plus sync-on-resume.

## Limitations

- **No push.** iCloud offers none to third-party CalDAV clients — only Apple's
  own apps get it. Everything here polls.
- Changes cross **only while the Mac is awake** with the agent running. The
  phone still works anywhere; it just sees the last mirrored state.
- Smart lists, tags, flagged, location and "when messaging" reminders aren't in
  the VTODO spec and cannot cross.
- Lists stored "On My Mac" never reach iCloud.

## Notes for anyone building on this

Things that cost real time:

- **`HttpURLConnection` on Android rejects `PROPFIND`/`REPORT`** with
  `ProtocolException` — which is all of CalDAV. Hence OkHttp.
- **Redirects silently break DAV.** iCloud redirects to a partition host;
  OkHttp preserves the method for `PROPFIND` but turns a redirected `REPORT`
  into a `GET`, and `URLSession` does its own rewriting. Both clients follow
  redirects manually, replaying method and body.
- **An iCloud calendar home contains collections that 403 a query** (inbox,
  outbox, shares). Try each one; don't trust the first.
- **Don't compare the two sides' serialisations to each other.** The same
  reminder differs between EventKit and iCalendar text, so every item looks
  changed on both sides and they overwrite each other forever. Store each
  side's own last-seen state.
- **A sync mapping file is a cache, not a source of truth.** If it can't be
  read, pair items by title before creating anything, or you duplicate the
  whole account.
