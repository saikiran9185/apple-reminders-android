import Foundation
import EventKit

struct AppleItem {
    var id: String
    var title: String
    var notes: String?
    var due: Date?
    var allDay: Bool
    var completed: Bool
    var modified: Date

    var fingerprint: String {
        // Rounded to the minute and trimmed: the two sides store the same
        // reminder with different precision and whitespace.
        let minute = due.map { Int($0.timeIntervalSince1970 / 60) } ?? 0
        let note = (notes ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(title.trimmingCharacters(in: .whitespacesAndNewlines))|\(note)|\(minute)|\(allDay)|\(completed)"
    }
}

/// EventKit access to the real, upgraded Reminders — the only door into them.
/// iCloud's CalDAV endpoint serves an abandoned legacy container instead.
actor RemindersStore {
    private let store = EKEventStore()

    @discardableResult
    func requestAccess() async -> Bool {
        (try? await store.requestFullAccessToReminders()) ?? false
    }

    func listNames() -> [(id: String, title: String)] {
        store.calendars(for: .reminder).map { ($0.calendarIdentifier, $0.title) }
    }

    func items() async -> [AppleItem] {
        let calendars = store.calendars(for: .reminder)
        guard !calendars.isEmpty else { return [] }
        let predicate = store.predicateForReminders(in: calendars)
        let found: [EKReminder] = await withCheckedContinuation { cont in
            store.fetchReminders(matching: predicate) { cont.resume(returning: $0 ?? []) }
        }
        return found.map { r in
            var due: Date?
            var allDay = true
            if let comps = r.dueDateComponents {
                due = Calendar.current.date(from: comps)
                allDay = comps.hour == nil
            }
            return AppleItem(id: r.calendarItemIdentifier,
                             title: r.title ?? "",
                             notes: r.notes,
                             due: due,
                             allDay: allDay,
                             completed: r.isCompleted,
                             modified: r.lastModifiedDate ?? .distantPast)
        }
    }

    /// Creates when `id` is nil, otherwise edits in place. Returns the item id.
    @discardableResult
    func upsert(id: String?, title: String, notes: String?, due: Date?,
                allDay: Bool, completed: Bool) throws -> String {
        let reminder: EKReminder
        if let id, let existing = store.calendarItem(withIdentifier: id) as? EKReminder {
            reminder = existing
        } else {
            reminder = EKReminder(eventStore: store)
            reminder.calendar = store.defaultCalendarForNewReminders()
        }
        reminder.title = title
        reminder.notes = notes
        reminder.isCompleted = completed
        if !completed { reminder.completionDate = nil }

        reminder.alarms?.forEach { reminder.removeAlarm($0) }
        if let due {
            let units: Set<Calendar.Component> = allDay
                ? [.year, .month, .day]
                : [.year, .month, .day, .hour, .minute]
            reminder.dueDateComponents = Calendar.current.dateComponents(units, from: due)
            if !allDay { reminder.addAlarm(EKAlarm(absoluteDate: due)) }
        } else {
            reminder.dueDateComponents = nil
        }

        try store.save(reminder, commit: true)
        return reminder.calendarItemIdentifier
    }

    func delete(id: String) throws {
        guard let r = store.calendarItem(withIdentifier: id) as? EKReminder else { return }
        try store.remove(r, commit: true)
    }
}

enum BridgeError: Error, LocalizedError {
    case notFound
    case badRequest(String)

    var errorDescription: String? {
        switch self {
        case .notFound: return "Not found"
        case .badRequest(let m): return m
        }
    }
}
