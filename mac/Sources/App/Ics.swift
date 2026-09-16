import Foundation

/// The same small VTODO subset the Android side speaks, so both ends agree.
enum Ics {
    static func parse(_ raw: String, href: String, etag: String?) -> CalDav.Item {
        let unfolded = raw.replacingOccurrences(of: "\r\n ", with: "")
                          .replacingOccurrences(of: "\n ", with: "")
        var item = CalDav.Item(uid: UUID().uuidString, href: href, etag: etag,
                               title: "", notes: nil, due: nil, allDay: false, completed: false)
        var inTodo = false

        for line in unfolded.components(separatedBy: .newlines) {
            if line.hasPrefix("BEGIN:VTODO") { inTodo = true; continue }
            if line.hasPrefix("END:VTODO") { break }
            guard inTodo, let colon = line.firstIndex(of: ":") else { continue }

            let head = String(line[line.startIndex..<colon])
            let value = String(line[line.index(after: colon)...])
            let name = head.components(separatedBy: ";")[0].uppercased()

            switch name {
            case "UID":         item.uid = value
            case "SUMMARY":     item.title = unescape(value)
            case "DESCRIPTION": item.notes = unescape(value)
            case "STATUS":      if value.uppercased() == "COMPLETED" { item.completed = true }
            case "COMPLETED":   item.completed = true
            case "DUE":
                item.allDay = head.uppercased().contains("VALUE=DATE") || value.count == 8
                item.due = date(from: value, head: head)
            default: break
            }
        }
        return item
    }

    static func write(_ item: CalDav.Item) -> String {
        var s = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//saikiran//RemindersBridge//EN\r\n"
        s += "BEGIN:VTODO\r\nUID:\(item.uid)\r\n"
        s += "DTSTAMP:\(stamp(Date()))\r\n"
        s += "SUMMARY:\(escape(item.title))\r\n"
        if let notes = item.notes, !notes.isEmpty { s += "DESCRIPTION:\(escape(notes))\r\n" }
        if let due = item.due {
            if item.allDay {
                let f = DateFormatter()
                f.dateFormat = "yyyyMMdd"
                s += "DUE;VALUE=DATE:\(f.string(from: due))\r\n"
            } else {
                s += "DUE:\(stamp(due))\r\n"
                s += "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:\(escape(item.title))\r\n"
                s += "TRIGGER;VALUE=DATE-TIME:\(stamp(due))\r\nEND:VALARM\r\n"
            }
        }
        s += item.completed
            ? "STATUS:COMPLETED\r\nPERCENT-COMPLETE:100\r\nCOMPLETED:\(stamp(Date()))\r\n"
            : "STATUS:NEEDS-ACTION\r\n"
        s += "END:VTODO\r\nEND:VCALENDAR\r\n"
        return s
    }

    private static func date(from value: String, head: String) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        if value.count == 8 {
            f.dateFormat = "yyyyMMdd"
            f.timeZone = .current
            return f.date(from: value)
        }
        if value.hasSuffix("Z") {
            f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
            f.timeZone = TimeZone(identifier: "UTC")
            return f.date(from: value)
        }
        f.dateFormat = "yyyyMMdd'T'HHmmss"
        if let range = head.range(of: "TZID=") {
            let tz = head[range.upperBound...].components(separatedBy: ";")[0]
            f.timeZone = TimeZone(identifier: String(tz)) ?? .current
        } else {
            f.timeZone = .current
        }
        return f.date(from: value)
    }

    private static func stamp(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: d)
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: ";", with: "\\;")
         .replacingOccurrences(of: ",", with: "\\,")
         .replacingOccurrences(of: "\n", with: "\\n")
    }

    private static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\n", with: "\n")
         .replacingOccurrences(of: "\\,", with: ",")
         .replacingOccurrences(of: "\\;", with: ";")
         .replacingOccurrences(of: "\\\\", with: "\\")
    }
}
