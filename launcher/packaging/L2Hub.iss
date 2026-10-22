; Установщик L2Hub (docs/RELEASE.md, «Программа и установщик»): на пользователя, без прав администратора,
; в %LOCALAPPDATA%\Programs\L2Hub. Ставит программу (dist\L2Hub после packaging\build.py) и спрашивает папку
; данных — runtime, servers, data, profiles; она пишется в %LOCALAPPDATA%\L2Hub\settings.json, её читает paths.HOME.
; Деинсталлятор убирает только программу: данные (базы, сохранения, бэкапы) остаются.
; Сборка: packaging\build.py --installer (ISCC с /DAppVersion=<версия из l2hub\__init__.py>).

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{6C1F4E0B-2D7A-4B8E-9F3C-5A1D2E7B4C90}
AppName=L2Hub
AppVersion={#AppVersion}
AppVerName=L2Hub {#AppVersion}
AppPublisher=L2Hub
DefaultDirName={localappdata}\Programs\L2Hub
DefaultGroupName=L2Hub
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=L2Hub-{#AppVersion}-setup
SetupIconFile=..\l2hub\ui\assets\icon.ico
UninstallDisplayIcon={app}\L2Hub.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
ru.DataTitle=Папка данных
ru.DataSub=Где L2Hub держит серверы, базы, рантаймы и бэкапы
ru.DataText=Сюда ляжет всё тяжёлое: Java, MariaDB, исходники и сборки серверов, базы и бэкапы — десятки гигабайт. Программа и папка данных живут раздельно: при удалении L2Hub данные остаются.
ru.DataBad=Папка данных — только латиница, цифры, точка, дефис и подчёркивание, без пробелов: на кириллице и пробелах ломаются Java и MariaDB.
ru.Desktop=Ярлык на рабочем столе
en.DataTitle=Data folder
en.DataSub=Where L2Hub keeps servers, databases, runtimes and backups
en.DataText=Everything heavy goes here: Java, MariaDB, server sources and builds, databases and backups — tens of gigabytes. The program and the data folder are separate: uninstalling L2Hub keeps the data.
en.DataBad=Data folder: Latin letters, digits, dot, dash and underscore only, no spaces — Java and MariaDB break on Cyrillic and spaces.
en.Desktop=Desktop shortcut
ru.NoWebView2=Окну L2Hub нужен Microsoft Edge WebView2 Runtime, а его на этом компьютере нет (в Windows 11 он есть всегда, в Windows 10 — не всегда).%n%nОткрыть страницу загрузки Microsoft? Поставь «Evergreen Bootstrapper» и запусти L2Hub — установка продолжится в любом случае.
en.NoWebView2=The L2Hub window needs the Microsoft Edge WebView2 Runtime, and it is not installed here (Windows 11 always has it, Windows 10 not always).%n%nOpen the Microsoft download page? Install the "Evergreen Bootstrapper", then start L2Hub — setup continues either way.

[Tasks]
Name: "desktop"; Description: "{cm:Desktop}"

[Files]
Source: "..\dist\L2Hub\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; Старые файлы программы прошлой версии (не данные): иначе от удалённого модуля остался бы мусор в _internal.
Type: filesandordirs; Name: "{app}\_internal"

[Icons]
Name: "{autoprograms}\L2Hub"; Filename: "{app}\L2Hub.exe"
Name: "{autodesktop}\L2Hub"; Filename: "{app}\L2Hub.exe"; Tasks: desktop

[Run]
Filename: "{app}\L2Hub.exe"; Description: "{cm:LaunchProgram,L2Hub}"; Flags: nowait postinstall skipifsilent
; Обновление из окна (updates.launch: /SILENT /UPDATE=1) — L2Hub закрылся перед установкой и открывается уже новым.
Filename: "{app}\L2Hub.exe"; Flags: nowait; Check: IsUpdate

[Code]
var
  DataPage: TInputDirWizardPage;

function SettingsFile: String;
begin
  Result := ExpandConstant('{localappdata}\L2Hub\settings.json');
end;

{ Папка данных из прежней установки: "home" в settings.json; иначе C:\L2Hub. }
function CurrentHome: String;
var
  Raw: AnsiString;
  S: String;
  P, Q: Integer;
begin
  Result := 'C:\L2Hub';
  if LoadStringFromFile(SettingsFile, Raw) then
  begin
    S := String(Raw);
    P := Pos('"home"', S);
    if P > 0 then
    begin
      S := Copy(S, P + 6, Length(S));
      P := Pos('"', S);
      S := Copy(S, P + 1, Length(S));
      Q := Pos('"', S);
      if Q > 1 then
      begin
        S := Copy(S, 1, Q - 1);
        StringChangeEx(S, '\\', '\', True);
        Result := S;
      end;
    end;
  end;
end;

{ То же правило, что paths.check_home: X:\ и дальше латиница, цифры, . - _ и \. }
function HomeOk(const Path: String): Boolean;
var
  I: Integer;
  C: Char;
begin
  Result := (Length(Path) >= 3) and (Path[2] = ':') and (Path[3] = '\');
  if not Result then Exit;
  C := Path[1];
  Result := ((C >= 'A') and (C <= 'Z')) or ((C >= 'a') and (C <= 'z'));
  for I := 4 to Length(Path) do
  begin
    C := Path[I];
    if not (((C >= 'A') and (C <= 'Z')) or ((C >= 'a') and (C <= 'z')) or ((C >= '0') and (C <= '9'))
            or (C = '.') or (C = '-') or (C = '_') or (C = '\')) then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

{ WebView2 Runtime: версия ("pv") в ключах EdgeUpdate для его GUID — машинная установка (64- и 32-битный реестр)
  или на пользователя. Пусто или 0.0.0.0 — рантайма нет: окно pywebview не откроется. }
function WebView2Version: String;
var
  Key: String;
begin
  Key := 'Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  if not RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\' + Key, 'pv', Result) then
    if not RegQueryStringValue(HKLM, 'SOFTWARE\' + Key, 'pv', Result) then
      if not RegQueryStringValue(HKCU, 'Software\' + Key, 'pv', Result) then
        Result := '';
end;

function IsUpdate: Boolean;
begin
  Result := ExpandConstant('{param:UPDATE|0}') = '1';
end;

function InitializeSetup: Boolean;
var
  V: String;
  Code: Integer;
begin
  Result := True;
  V := WebView2Version;
  if ((V = '') or (V = '0.0.0.0')) and not WizardSilent then
    if MsgBox(CustomMessage('NoWebView2'), mbConfirmation, MB_YESNO) = IDYES then
      ShellExec('open', 'https://developer.microsoft.com/microsoft-edge/webview2/', '', '', SW_SHOWNORMAL, ewNoWait, Code);
end;

procedure InitializeWizard;
begin
  DataPage := CreateInputDirPage(wpSelectDir, CustomMessage('DataTitle'), CustomMessage('DataSub'),
    CustomMessage('DataText'), False, '');
  DataPage.Add('');
  { /HOME=<папка> в командной строке — для тихой установки (/VERYSILENT). }
  DataPage.Values[0] := ExpandConstant('{param:HOME|' + CurrentHome + '}');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = DataPage.ID) and not HomeOk(DataPage.Values[0]) then
  begin
    MsgBox(CustomMessage('DataBad'), mbError, MB_OK);
    Result := False;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Home: String;
begin
  if CurStep = ssPostInstall then
  begin
    Home := RemoveBackslashUnlessRoot(DataPage.Values[0]);
    ForceDirectories(Home);
    ForceDirectories(ExtractFileDir(SettingsFile));
    StringChangeEx(Home, '\', '\\', True);
    SaveStringToFile(SettingsFile, '{"home": "' + Home + '"}' + #13#10, False);
  end;
end;
