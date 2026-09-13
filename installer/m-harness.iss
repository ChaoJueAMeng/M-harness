; M Bot 桌面端安装脚本（Inno Setup 6）
; 由 scripts/publish-desktop.ps1 调用 ISCC 编译。

#ifndef MyAppVersion
  #define MyAppVersion "0.1.0"
#endif

#define MyAppName "M Bot"
#define MyAppPublisher "M Bot"
#define MyAppExeName "MHarness.Desktop.exe"
#define MyAppSource "..\dist\app"

[Setup]
AppId={{8F3C1A2E-9B47-4D6E-A1C8-E7B5D04F92A3}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppCopyright={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\M Bot
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=M-Bot-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
SetupIconFile=..\agent-desktop\Assets\app.ico
CloseApplications=yes
RestartApplications=no
MinVersion=10.0.17763
SetupLogging=yes

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#MyAppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "Install.ps1,Uninstall.ps1"

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
