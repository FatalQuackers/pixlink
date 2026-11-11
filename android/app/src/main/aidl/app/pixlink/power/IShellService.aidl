package app.pixlink.power;

// Runs inside Shizuku's process with shell (ADB) permission.
interface IShellService {
    // Required by Shizuku: called when the service is unbound.
    void destroy() = 16777114;

    // Runs a shell command and returns stdout+stderr.
    String exec(String command) = 1;

    // Turns Wi-Fi hotspot (tethering) on or off. Returns true if the request was accepted.
    boolean setWifiTethering(boolean enabled) = 2;
}
