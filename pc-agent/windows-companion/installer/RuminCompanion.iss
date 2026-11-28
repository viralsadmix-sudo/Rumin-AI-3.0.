#define MyAppName "Rumin AI Windows Companion"
#define MyAppVersion "0.6.1"
#define MyAppPublisher "SABBIR RUBEL TECH"
#define MyAppExeName "RuminCompanion.exe"

[Setup]
AppId={{7C18B9F4-1F43-4C86-A6A0-5D7E9A5F3061}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\Rumin AI Windows Companion
DefaultGroupName=Rumin AI Windows Companion
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
OutputDir=Output
OutputBaseFilename=Rumin-Windows-Companion-Setup-0.6.1
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#MyAppName}
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Rumin AI Windows Companion"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Rumin AI Windows Companion"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch Rumin AI Windows Companion"; Flags: postinstall nowait skipifsilent
