import Foundation
import UserNotifications

/// Apps installed with a free Apple ID (AltStore, Sideloadly, SideStore) stop opening after 7 days.
/// Pixlink reads its own expiry date and reminds you to refresh it a day before.
enum InstallInfo {
    /// When this install stops opening, or nil for App Store / TestFlight / unlimited installs.
    static let expiry: Date? = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"),
              let end = text.range(of: "</plist>") else { return nil }
        let plistText = String(text[start.lowerBound..<end.upperBound])
        guard let plistData = plistText.data(using: .isoLatin1),
              let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any] else { return nil }
        return plist["ExpirationDate"] as? Date
    }()

    static var daysLeft: Int? {
        guard let expiry else { return nil }
        return max(0, Calendar.current.dateComponents([.day], from: Date(), to: expiry).day ?? 0)
    }

    /// Schedules "Refresh Tethr Link" for a day before it expires (asks for notification permission once).
    static func scheduleReminder() {
        guard let expiry else { return }
        let fireDate = expiry.addingTimeInterval(-24 * 3600)
        guard fireDate > Date() else { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "Refresh Tethr Link"
            content.body = "Tethr Link stops opening tomorrow. Open AltStore or SideStore and tap Refresh, or reinstall it with Sideloadly."
            content.sound = .default
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
            let request = UNNotificationRequest(identifier: "refresh", content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            center.removePendingNotificationRequests(withIdentifiers: ["refresh"])
            center.add(request)
        }
    }
}
