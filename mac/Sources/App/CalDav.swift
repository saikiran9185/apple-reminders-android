import Foundation

/// CalDAV client for the Mac side of the bridge.
///
/// URLSession allows arbitrary HTTP methods, so unlike the Android side this
/// needs no third-party HTTP library.
struct CalDav {
    struct Item {
        var uid: String
        var href: String
        var etag: String?
        var title: String
        var notes: String?
        var due: Date?
        var allDay: Bool
        var completed: Bool

        /// Everything that matters for change detection, in one comparable string.
        var fingerprint: String {
            // Rounded to the minute and trimmed: the two sides store the same
        // reminder with different precision and whitespace.
        let minute = due.map { Int($0.timeIntervalSince1970 / 60) } ?? 0
        let note = (notes ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(title.trimmingCharacters(in: .whitespacesAndNewlines))|\(note)|\(minute)|\(allDay)|\(completed)"
        }
    }

    let user: String
    let password: String
    var root = "https://caldav.icloud.com"

    private var auth: String {
        "Basic " + Data("\(user):\(password)".utf8).base64EncodedString()
    }

    /// URLSession rewrites the method when it follows a redirect, so a REPORT
    /// arrives at iCloud's partition host as a GET and comes back 403. This
    /// session refuses to follow; `send` replays the request itself.
    private static let session: URLSession = {
        URLSession(configuration: .default, delegate: NoFollow(), delegateQueue: nil)
    }()

    private final class NoFollow: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    /// Replays a request across redirects with its method and body intact.
    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        var req = request
        for _ in 0..<5 {
            let (data, resp) = try await Self.session.data(for: req)
            guard let http = resp as? HTTPURLResponse else {
                throw BridgeError.badRequest("No HTTP response from iCloud.")
            }
            let redirect = [301, 302, 303, 307, 308].contains(http.statusCode)
            guard redirect, let location = http.value(forHTTPHeaderField: "Location"),
                  let next = URL(string: location, relativeTo: req.url) else {
                return (data, http)
            }
            req.url = next        // same method, same body, same headers
        }
        throw BridgeError.badRequest("Too many redirects from iCloud.")
    }

    // MARK: - Discovery

    /// Every collection advertising VTODO support. An iCloud home also holds
    /// inbox/outbox/shared collections that answer a REPORT with 403, so the
    /// caller has to try them in turn rather than trusting the first.
    func taskCollections() async throws -> [(href: String, name: String)] {
        let base = root.hasSuffix("/") ? root : root + "/"

        let rootXML = try await dav("PROPFIND", base, depth: 0,
            body: "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>")
        guard let principal = DavXML.parse(rootXML).first(where: { $0["principal-href"] != nil })?["principal-href"] else {
            throw BridgeError.badRequest("iCloud returned no principal. Check the Apple ID and app-specific password.")
        }

        let homeXML = try await dav("PROPFIND", try url(base, principal), depth: 0,
            body: "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><c:calendar-home-set/></d:prop></d:propfind>")
        guard let home = DavXML.parse(homeXML).first(where: { $0["home-href"] != nil })?["home-href"] else {
            throw BridgeError.badRequest("iCloud returned no calendar home.")
        }

        let homeURL = try url(base, home)
        let listXML = try await dav("PROPFIND", homeURL, depth: 1,
            body: "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/></d:prop></d:propfind>")

        var found: [(href: String, name: String)] = []
        for block in DavXML.parse(listXML) where block["vtodo"] == "1" {
            guard let href = block["href"] else { continue }
            found.append((try url(homeURL, href), block["displayname"] ?? "Reminders"))
        }
        guard !found.isEmpty else {
            throw BridgeError.badRequest("No task collection in this iCloud account.")
        }
        return found
    }

    // MARK: - Read

    func items(in collection: String) async throws -> [Item] {
        let xml = try await dav("REPORT", collection, depth: 1, body:
            "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
            + "<d:prop><d:getetag/><c:calendar-data/></d:prop>"
            + "<c:filter><c:comp-filter name=\"VCALENDAR\">"
            + "<c:comp-filter name=\"VTODO\"/></c:comp-filter></c:filter></c:calendar-query>")

        return try DavXML.parse(xml).compactMap { block in
            guard let data = block["calendar-data"], data.contains("VTODO"),
                  let href = block["href"] else { return nil }
            return Ics.parse(data, href: try url(collection, href), etag: block["getetag"])
        }
    }

    // MARK: - Write

    @discardableResult
    func put(_ item: Item, in collection: String) async throws -> String {
        let href = item.href.isEmpty
            ? collection + (collection.hasSuffix("/") ? "" : "/") + item.uid + ".ics"
            : item.href
        guard let target = URL(string: href) else {
            throw BridgeError.badRequest("Bad task address: \(href)")
        }
        var req = URLRequest(url: target)
        req.httpMethod = "PUT"
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        req.setValue("text/calendar; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let etag = item.etag { req.setValue(etag, forHTTPHeaderField: "If-Match") }
        req.httpBody = Data(Ics.write(item).utf8)

        let (_, http) = try await send(req)
        if !(200..<300).contains(http.statusCode), http.statusCode != 412 {
            throw BridgeError.badRequest("PUT failed: HTTP \(http.statusCode)")
        }
        return href
    }

    func delete(href: String) async throws {
        guard let target = URL(string: href) else { return }
        var req = URLRequest(url: target)
        req.httpMethod = "DELETE"
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        _ = try? await send(req)
    }

    // MARK: - Plumbing

    private func dav(_ method: String, _ urlString: String, depth: Int, body: String) async throws -> String {
        guard let target = URL(string: urlString) else {
            throw BridgeError.badRequest("Bad server address: \(urlString)")
        }
        var req = URLRequest(url: target)
        req.httpMethod = method
        req.setValue(auth, forHTTPHeaderField: "Authorization")
        req.setValue("\(depth)", forHTTPHeaderField: "Depth")
        req.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(body.utf8)

        let (data, http) = try await send(req)
        if http.statusCode == 401 {
            throw BridgeError.badRequest("iCloud rejected the password. Use an app-specific one from appleid.apple.com.")
        }
        if http.statusCode != 207, !(200..<300).contains(http.statusCode) {
            throw BridgeError.badRequest("HTTP \(http.statusCode) on \(method)")
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Resolves a possibly-relative href, failing loudly instead of force-unwrapping.
    private func url(_ base: String, _ href: String) throws -> String {
        let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw BridgeError.badRequest("iCloud returned an empty address.") }
        if trimmed.hasPrefix("http") { return trimmed }
        guard let baseURL = URL(string: base),
              let resolved = URL(string: trimmed, relativeTo: baseURL)?.absoluteString else {
            throw BridgeError.badRequest("Could not resolve \(trimmed)")
        }
        return resolved
    }
}

/// A real XML parser for 207 Multi-Status. The previous hand-rolled string
/// slicing produced an href that wasn't a URL at all, which crashed the app.
final class DavXML: NSObject, XMLParserDelegate {
    private var blocks: [[String: String]] = []
    private var current: [String: String]?
    private var stack: [String] = []
    private var text = ""

    static func parse(_ xml: String) -> [[String: String]] {
        let handler = DavXML()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.delegate = handler
        parser.parse()
        return handler.blocks
    }

    private func local(_ name: String) -> String {
        (name.contains(":") ? String(name.split(separator: ":").last!) : name).lowercased()
    }

    func parser(_ p: XMLParser, didStartElement name: String, namespaceURI: String?,
                qualifiedName: String?, attributes attrs: [String: String]) {
        let n = local(name)
        stack.append(n)
        text = ""
        if n == "response" { current = [:] }
        if n == "comp", attrs["name"]?.uppercased() == "VTODO" { current?["vtodo"] = "1" }
    }

    func parser(_ p: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ p: XMLParser, didEndElement name: String, namespaceURI: String?,
                qualifiedName: String?) {
        let n = local(name)
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)

        if n == "href" {
            // An href means different things depending on what encloses it.
            if stack.contains("current-user-principal") {
                current?["principal-href"] = value
            } else if stack.contains("calendar-home-set") {
                current?["home-href"] = value
            } else if current?["href"] == nil {
                current?["href"] = value
            }
        } else if ["displayname", "getetag", "calendar-data"].contains(n), !value.isEmpty {
            current?[n] = value
        } else if n == "response", let block = current {
            blocks.append(block)
            current = nil
        }
        if !stack.isEmpty { stack.removeLast() }
        text = ""
    }
}
