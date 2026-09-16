import Foundation

/// Two-way reconciliation between Apple Reminders and the iCloud CalDAV
/// collection the phone talks to.
///
/// Neither side can see the other, so a mapping file remembers which reminder
/// corresponds to which VTODO, plus what each looked like last time. Without
/// that record a deletion on one side is indistinguishable from a creation on
/// the other.
actor Mirror {
    struct Link: Codable {
        var appleId: String
        var uid: String
        var href: String
        /// Each side's own last-seen state, kept separately. Comparing Apple's
        /// fingerprint against CalDAV's directly never matched — the same
        /// reminder serialises differently on each side — so both ends looked
        /// permanently "changed" and overwrote each other every cycle.
        var appleSeen: String
        var remoteSeen: String
    }

    struct Report {
        var pushed = 0, pulled = 0, deleted = 0
        var summary: String { "↑\(pushed) ↓\(pulled) ✕\(deleted)" }
    }

    /// Apple writes these into the legacy collection to explain the upgrade.
    /// Copying them back into Reminders would be absurd.
    private static let tombstones = [
        "the creator of this list has upgraded",
        "where are my reminders",
    ]

    /// Matched on a stem rather than the whole string: the real title ends in a
    /// full stop that the stored text lacked, so exact equality let it through.
    private static func isTombstone(_ title: String) -> Bool {
        let t = title.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        return tombstones.contains { t.hasPrefix($0) }
    }

    /// Titles are the only thing both sides are guaranteed to agree on.
    private static func key(_ title: String) -> String {
        title.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private let reminders = RemindersStore()
    private var links: [Link] = []
    private var linksURL: URL {
        Store.dir.appendingPathComponent("mapping.json")
    }

    func run(user: String, password: String) async throws -> Report {
        guard await reminders.requestAccess() else {
            throw BridgeError.badRequest("Reminders access denied — allow it in System Settings → Privacy & Security → Reminders.")
        }
        // One link per item, each way. A corrupted or half-written mapping
        // otherwise leaves two links racing over the same reminder.
        var seenApple = Set<String>(), seenUid = Set<String>()
        links = (Store.read([Link].self, from: linksURL) ?? []).filter {
            guard !seenApple.contains($0.appleId), !seenUid.contains($0.uid) else { return false }
            seenApple.insert($0.appleId); seenUid.insert($0.uid)
            return true
        }

        let dav = CalDav(user: user, password: password)

        // Use the first collection that actually answers. The rest of the
        // account's collections are inboxes and shares that refuse a query.
        var collection: String?
        var remote: [CalDav.Item] = []
        var refusals: [String] = []
        for candidate in try await dav.taskCollections() {
            do {
                remote = try await dav.items(in: candidate.href)
                    .filter { !Self.isTombstone($0.title) }
                collection = candidate.href
                break
            } catch {
                refusals.append("\(candidate.name): \(error.localizedDescription)")
            }
        }
        guard let collection else {
            throw BridgeError.badRequest("No readable task list. " + refusals.joined(separator: "; "))
        }
        // Clean up any tombstone an earlier, stricter filter let through.
        var local: [AppleItem] = []
        for item in await reminders.items() {
            if Self.isTombstone(item.title) {
                try? await reminders.delete(id: item.id)
                links.removeAll { $0.appleId == item.id }
            } else {
                local.append(item)
            }
        }

        var report = Report()
        var byApple = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        var byUid = Dictionary(uniqueKeysWithValues: remote.map { ($0.uid, $0) })
        var next: [Link] = []

        // 1. Pairs we already know about.
        for link in links {
            let apple = byApple.removeValue(forKey: link.appleId)
            let caldav = byUid.removeValue(forKey: link.uid)

            switch (apple, caldav) {
            case (nil, nil):
                continue                                   // gone from both
            case (nil, let remote?):
                try await dav.delete(href: remote.href)     // deleted on the Mac
                report.deleted += 1
            case (let apple?, nil):
                try await reminders.delete(id: apple.id)    // deleted on the phone
                report.deleted += 1
            case (let apple?, let remote?):
                let appleChanged = apple.fingerprint != link.appleSeen
                let remoteChanged = remote.fingerprint != link.remoteSeen

                if appleChanged {
                    // The Mac wins when both changed.
                    var item = remote
                    item.title = apple.title; item.notes = apple.notes
                    item.due = apple.due; item.allDay = apple.allDay
                    item.completed = apple.completed
                    let href = try await dav.put(item, in: collection)
                    report.pushed += 1
                    next.append(Link(appleId: apple.id, uid: item.uid, href: href,
                                     appleSeen: apple.fingerprint,
                                     remoteSeen: item.fingerprint))
                } else if remoteChanged {
                    // Changed on the phone — ticking something off counts.
                    try await reminders.upsert(id: apple.id, title: remote.title,
                                               notes: remote.notes, due: remote.due,
                                               allDay: remote.allDay, completed: remote.completed)
                    report.pulled += 1
                    // Re-read so the stored Apple fingerprint matches what
                    // EventKit actually saved, not what we asked it to save.
                    let saved = await reminders.items().first { $0.id == apple.id }
                    next.append(Link(appleId: apple.id, uid: remote.uid, href: remote.href,
                                     appleSeen: saved?.fingerprint ?? apple.fingerprint,
                                     remoteSeen: remote.fingerprint))
                } else {
                    next.append(link)                      // nothing to do
                }
            }
        }

        // 2. Before creating anything, pair leftovers by title. The mapping
        //    file is only a cache; losing it must not duplicate the account,
        //    which is exactly what happened when its format changed.
        for (appleId, apple) in byApple {
            let key = Self.key(apple.title)
            guard let match = byUid.values.first(where: { Self.key($0.title) == key }) else { continue }
            byApple.removeValue(forKey: appleId)
            byUid.removeValue(forKey: match.uid)
            next.append(Link(appleId: appleId, uid: match.uid, href: match.href,
                             appleSeen: apple.fingerprint, remoteSeen: match.fingerprint))
        }

        // 3. Anything left on the server that duplicates something already
        //    linked is a stray copy from that episode — remove it.
        let linkedKeys = Set(next.map { Self.key($0.appleSeen.components(separatedBy: "|")[0]) })
        for (uid, remote) in byUid where linkedKeys.contains(Self.key(remote.title)) {
            try await dav.delete(href: remote.href)
            byUid.removeValue(forKey: uid)
            report.deleted += 1
        }

        // 4. New on the Mac → create in iCloud for the phone to find.
        for apple in byApple.values {
            var item = CalDav.Item(uid: UUID().uuidString.uppercased(), href: "", etag: nil,
                                   title: apple.title, notes: apple.notes, due: apple.due,
                                   allDay: apple.allDay, completed: apple.completed)
            let href = try await dav.put(item, in: collection)
            item.href = href
            report.pushed += 1
            next.append(Link(appleId: apple.id, uid: item.uid, href: href,
                             appleSeen: apple.fingerprint, remoteSeen: item.fingerprint))
        }

        // 5. New on the phone → create in Reminders.
        for remote in byUid.values {
            let id = try await reminders.upsert(id: nil, title: remote.title,
                                                notes: remote.notes, due: remote.due,
                                                allDay: remote.allDay, completed: remote.completed)
            report.pulled += 1
            let saved = await reminders.items().first { $0.id == id }
            next.append(Link(appleId: id, uid: remote.uid, href: remote.href,
                             appleSeen: saved?.fingerprint ?? remote.fingerprint,
                             remoteSeen: remote.fingerprint))
        }

        links = next
        try? Store.write(next, to: linksURL)
        return report
    }
}

/// Where the bridge keeps its mapping.
enum Store {
    static let dir: URL = {
        let base = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/RemindersBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }()

    static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    static func write<T: Encodable>(_ value: T, to url: URL) throws {
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600],
                                               ofItemAtPath: url.path)
    }
}
