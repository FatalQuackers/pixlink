; Pixlink for Windows installer (Inno Setup 6).
; Installs for the current user only, so it never asks for admin rights.
; CI sets PIXLINK_VERSION and APP_DIR (the built app folder).

#define AppVersion GetEnv("PIXLINK_VERSION")
#define AppDir GetEnv("APP_DIR")

[Setup]
AppId={{8F3C2B1A-6D4E-4C7B-9A21-5E0F1C2D3B4A}
AppName=Pixlink
AppVersion={#AppVersion}
AppVerName=Pixlink {#AppVersion}
AppPublisher=Pixlink (open source)
AppPublisherURL=https://fatalquackers.github.io/pixlink
AppSupportURL=https://github.com/FatalQuackers/pixlink/issues
AppUpdatesURL=https://github.com/FatalQuackers/pixlink/releases
DefaultDirName={localappdata}\Programs\Pixlink
DefaultGroupName=Pixlink
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=Pixlink-Setup-x64
SetupIconFile=..\Pixlink\Assets\Pixlink.ico
UninstallDisplayIcon={app}\Pixlink.exe
UninstallDisplayName=Pixlink
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no

[Tasks]
Name: desktopicon; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: startup; Description: "&Start Pixlink when I sign in to Windows"; GroupDescription: "Startup:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Pixlink"; Filename: "{app}\Pixlink.exe"; Comment: "Your Pixel, on your PC"
Name: "{userdesktop}\Pixlink"; Filename: "{app}\Pixlink.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Pixlink"; ValueData: """{app}\Pixlink.exe"" --background"; Tasks: startup

[Run]
Filename: "{app}\Pixlink.exe"; Description: "Open Pixlink"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Pixlink.exe /F"; Flags: runhidden; RunOnceId: "StopPixlink"
Filename: "{sys}\reg.exe"; Parameters: "delete HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v Pixlink /f"; Flags: runhidden; RunOnceId: "RemoveStartup"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
