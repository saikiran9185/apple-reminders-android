import SwiftUI
import EventKit
import ServiceManagement

@main
struct BridgeApp: App {
    @StateObject private var model = BridgeModel()

    var body: some Scene {
        MenuBarExtra {
            Panel().environmentObject(model)
        } label: {
            Image(systemName: model.running ? "arrow.triangle.2.circlepath" : "checklist")
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class BridgeModel: ObservableObject {
    @Published var user = UserDefaults.standard.string(forKey: "user") ?? ""
    @Published var password = ""
    @Published var status = "Not connected"
    @Published var running = false
    @Published var lastError: String?
    @Published var everySeconds = 30.0
    @Published var atLogin = SMAppService.mainApp.status == .enabled

    private let mirror = Mirror()
    private var timer: Timer?
    /// Our own writes fire EKEventStoreChanged too; without this the observer
    /// would re-trigger itself after every sync.
    private var ignoreChangesUntil = Date.distantPast

    init() {
        password = (try? String(contentsOf: Store.dir.appendingPathComponent("pw"),
                                encoding: .utf8)) ?? ""
        if !user.isEmpty && !password.isEmpty { start() }

        // React the moment Reminders changes, so adding one on the Mac reaches
        // the phone in seconds instead of waiting out the polling interval.
        NotificationCenter.default.addObserver(forName: .EKEventStoreChanged,
                                               object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, Date() > self.ignoreChangesUntil else { return }
                await self.sync()
            }
        }
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            try on ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister()
            atLogin = SMAppService.mainApp.status == .enabled
        } catch {
            lastError = error.localizedDescription
            atLogin = SMAppService.mainApp.status == .enabled
        }
    }

    func start() {
        UserDefaults.standard.set(user, forKey: "user")
        // The app password sits beside the mapping, readable only by this user.
        try? password.write(to: Store.dir.appendingPathComponent("pw"),
                            atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes(
            [.posixPermissions: 0o600],
            ofItemAtPath: Store.dir.appendingPathComponent("pw").path)

        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: everySeconds, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.sync() }
        }
        Task { await sync() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        status = "Paused"
    }

    func sync() async {
        guard !user.isEmpty, !password.isEmpty, !running else { return }
        running = true
        defer { running = false }
        do {
            let report = try await mirror.run(user: user, password: password)
            ignoreChangesUntil = Date().addingTimeInterval(5)
            lastError = nil
            status = "Synced \(report.summary) · \(Date().formatted(date: .omitted, time: .shortened))"
            log("ok \(report.summary)")
        } catch {
            lastError = error.localizedDescription
            status = "Failed"
            log("FAIL \(error.localizedDescription)")
        }
    }

    /// A menu bar popover is a poor place to read history from; the log keeps
    /// the last failure around after the panel closes.
    private func log(_ line: String) {
        let stamp = Date().formatted(date: .omitted, time: .standard)
        let entry = "[\(stamp)] \(line)\n"
        let url = Store.dir.appendingPathComponent("log.txt")
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(Data(entry.utf8))
            try? handle.close()
        } else {
            try? entry.write(to: url, atomically: true, encoding: .utf8)
        }
    }
}

struct Panel: View {
    @EnvironmentObject var model: BridgeModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Reminders Bridge").font(.headline)
                Spacer()
                if model.running { ProgressView().controlSize(.small) }
            }

            Text("Mirrors Apple Reminders to the iCloud collection your phone reads. Syncs on every change, and every 30s for the phone's side.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Divider()

            TextField("Apple ID", text: $model.user)
                .textFieldStyle(.roundedBorder)
            SecureField("App-specific password", text: $model.password)
                .textFieldStyle(.roundedBorder)

            Toggle("Start at login", isOn: Binding(
                get: { model.atLogin },
                set: { model.setLaunchAtLogin($0) }))
                .font(.caption)

            HStack {
                Button("Start") { model.start() }
                    .keyboardShortcut(.defaultAction)
                Button("Sync now") { Task { await model.sync() } }
                Button("Pause") { model.stop() }
                Spacer()
            }

            Divider()

            Text(model.status).font(.caption)
            if let err = model.lastError {
                Text(err).font(.caption2).foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
                    .buttonStyle(.link).font(.caption)
            }
        }
        .padding(14)
        .frame(width: 320)
    }
}
