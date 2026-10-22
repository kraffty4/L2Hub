# Посылает Ctrl+C консольному процессу: JVM на это выполняет shutdown-хуки и корректно завершается.
# Запускается отдельным скрытым процессом (см. Send-CtrlC в _common.ps1), потому что отцепляется
# от своей консоли и цепляется к консоли цели.
# Коды выхода: 0 — отправлено, 2 — не удалось подключиться к консоли цели, 3 — не удалось отправить.

param([Parameter(Mandatory = $true)][int]$TargetPid)

Add-Type -Namespace L2Hub -Name Console -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool FreeConsole();
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool AttachConsole(uint dwProcessId);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetConsoleCtrlHandler(IntPtr handlerRoutine, bool add);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);
'@

[void][L2Hub.Console]::FreeConsole()
if (-not [L2Hub.Console]::AttachConsole($TargetPid)) { exit 2 }
# Сами Ctrl+C игнорируем, иначе событие убьёт и этот процесс.
[void][L2Hub.Console]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
if (-not [L2Hub.Console]::GenerateConsoleCtrlEvent(0, 0)) { exit 3 }
exit 0
