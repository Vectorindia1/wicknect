import SwiftUI
import BackgroundTasks
import HostelCore

@main
struct HostelAutoLoginApp: App {
    static let refreshID = "com.hostelautologin.refresh"
    @Environment(\.scenePhase) private var phase

    init() {
        Store.shared.installLogSink()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshID, using: nil) { task in
            Self.handle(task as! BGAppRefreshTask)
        }
    }

    var body: some Scene {
        WindowGroup { ContentView() }
            .onChange(of: phase) { p in if p == .background { Self.schedule() } }
    }

    static func schedule() {
        let req = BGAppRefreshTaskRequest(identifier: refreshID)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    static func handle(_ task: BGAppRefreshTask) {
        schedule()
        let work = Task {
            let r = await Runner.runStored(quick: true)
            task.setTaskCompleted(success: r.outcome == .success)
        }
        task.expirationHandler = { work.cancel() }
    }
}
