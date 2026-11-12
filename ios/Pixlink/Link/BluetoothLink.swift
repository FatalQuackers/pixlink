import CoreBluetooth
import UIKit

/// Keeps a Bluetooth LE link to the PC. It does two things Wi-Fi can't on iPhone:
///  - Pairs the iPhone with the PC, which lets the PC read notifications through Apple's
///    Notification Center Service (the same way Phone Link works).
///  - Stays up when Pixlink isn't open: iOS keeps Bluetooth links alive and reconnects them,
///    so notifications and battery level keep reaching the PC.
@MainActor
final class BluetoothLink: NSObject, ObservableObject {
    static let shared = BluetoothLink()

    static let service = CBUUID(string: "6F3C1E9A-2B7D-4C5E-9A1F-8D2B3C4E5F61")
    static let pcIdChar = CBUUID(string: "6F3C1E9A-2B7D-4C5E-9A1F-8D2B3C4E5F62")
    static let statusChar = CBUUID(string: "6F3C1E9A-2B7D-4C5E-9A1F-8D2B3C4E5F63")
    static let keepAliveChar = CBUUID(string: "6F3C1E9A-2B7D-4C5E-9A1F-8D2B3C4E5F64")

    enum State: Equatable {
        case off            // not set up yet
        case bluetoothOff
        case notAllowed
        case searching
        case connecting
        case connected
        case wrongPC
    }

    @Published private(set) var state: State = .off

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var statusCharacteristic: CBCharacteristic?
    private let defaults = SharedStore.defaults

    /// Turned on by "Set up notifications"; remembered so the link comes back after a restart.
    var enabled: Bool {
        get { defaults.bool(forKey: "btEnabled") }
        set { defaults.set(newValue, forKey: "btEnabled") }
    }

    private var savedPeripheral: UUID? {
        get { defaults.string(forKey: "btPeripheral").flatMap(UUID.init) }
        set { defaults.set(newValue?.uuidString, forKey: "btPeripheral") }
    }

    private override init() {
        super.init()
        NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in BluetoothLink.shared.writeStatus() }
        }
        NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in BluetoothLink.shared.writeStatus() }
        }
    }

    /// Called at launch: resumes the link if it was set up before.
    func startIfEnabled() {
        if enabled && SharedStore.pc != nil { start() }
    }

    func start() {
        enabled = true
        if let central {
            if central.state == .poweredOn { connectOrScan() }
            return
        }
        // Creating the manager shows Apple's Bluetooth permission prompt the first time.
        central = CBCentralManager(delegate: self, queue: nil,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "app.pixlink.central"])
    }

    func stop() {
        enabled = false
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        central?.stopScan()
        peripheral = nil
        savedPeripheral = nil
        state = .off
    }

    private func connectOrScan() {
        guard let central, central.state == .poweredOn else { return }
        if let p = peripheral, p.state == .connected || p.state == .connecting { return }
        // Already connected by iOS (or another app)?
        if let p = central.retrieveConnectedPeripherals(withServices: [Self.service]).first {
            connect(p)
            return
        }
        // A PC we've paired with before: a pending connect that iOS completes whenever it's in range.
        if let id = savedPeripheral, let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            connect(p)
            return
        }
        state = .searching
        central.scanForPeripherals(withServices: [Self.service], options: nil)
    }

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        state = .connecting
        central?.connect(p, options: nil)
    }

    func writeStatus() {
        guard let p = peripheral, let c = statusCharacteristic, p.state == .connected else { return }
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let level = device.batteryLevel
        let battery: UInt8 = level < 0 ? 255 : UInt8(max(0, min(100, Int((level * 100).rounded()))))
        let charging: UInt8 = (device.batteryState == .charging || device.batteryState == .full) ? 1 : 0
        p.writeValue(Data([battery, charging]), for: c, type: .withResponse)
    }
}

extension BluetoothLink: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn: self.connectOrScan()
            case .unauthorized: self.state = .notAllowed
            case .poweredOff: self.state = .bluetoothOff
            default: break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]
        Task { @MainActor in
            if let p = restored?.first {
                self.peripheral = p
                p.delegate = self
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        Task { @MainActor in
            central.stopScan()
            self.connect(peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            peripheral.discoverServices([Self.service])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.peripheral = nil
            self.connectOrScan()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            guard self.enabled, self.state != .wrongPC else { return }
            // Reconnect whenever the PC is back in range, even with Pixlink closed.
            self.statusCharacteristic = nil
            self.connect(peripheral)
        }
    }
}

extension BluetoothLink: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else { return }
            peripheral.discoverCharacteristics([Self.pcIdChar, Self.statusChar, Self.keepAliveChar], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor in
            for c in service.characteristics ?? [] {
                switch c.uuid {
                case Self.pcIdChar: peripheral.readValue(for: c) // needs encryption → iOS asks to pair
                case Self.statusChar: self.statusCharacteristic = c
                case Self.keepAliveChar: peripheral.setNotifyValue(true, for: c)
                default: break
                }
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let value = characteristic.value
        let uuid = characteristic.uuid
        Task { @MainActor in
            guard uuid == Self.pcIdChar, let value, let pcId = String(data: value, encoding: .utf8) else { return }
            guard let pc = SharedStore.pc, pc.pcId.lowercased() == pcId.lowercased() else {
                // Another PC running Pixlink nearby: leave it alone and keep looking.
                self.central?.cancelPeripheralConnection(peripheral)
                self.peripheral = nil
                self.state = .searching
                self.central?.scanForPeripherals(withServices: [Self.service], options: nil)
                return
            }
            self.savedPeripheral = peripheral.identifier
            self.state = .connected
            self.writeStatus()
        }
    }
}
