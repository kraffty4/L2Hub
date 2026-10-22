# Запускает логин и гейм сборки в фоне и ждёт их порты. Логи — log\java0.log папок login и game.
# Вывод не перенаправляется намеренно: так у каждого процесса своя скрытая консоль,
# и server_stop.ps1 может послать ему Ctrl+C для корректной остановки.
# Пример: .\server_start.ps1 -Id ct26_highfive

param([Parameter(Mandatory = $true)][string]$Id)

. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'

$srv = Join-Path $Root "servers\$Id\server"
if (-not (Wait-Port 3306 1)) { throw 'MariaDB не запущена: сначала db_start.ps1' }
if (Test-Path (Join-Path $RunDir "$Id.json")) { throw "Похоже, $Id уже запущен: есть data\run\$Id.json" }
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

$java = "$JavaHome\bin\java.exe"
$pids = [ordered]@{}
$servers = @(
    @{ Name = 'login'; Jar = 'LoginServer.jar'; Port = 2106 },
    @{ Name = 'game'; Jar = 'GameServer.jar'; Port = 7777 }
)
foreach ($s in $servers) {
    $dir = Join-Path $srv $s.Name
    $argLine = (Get-Content "$dir\java.cfg" -TotalCount 1) + " -jar ../libs/$($s.Jar)"
    $p = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $dir -WindowStyle Hidden -PassThru
    $pids[$s.Name] = $p.Id
    ($pids | ConvertTo-Json) | Set-Content (Join-Path $RunDir "$Id.json")
    if (-not (Wait-Port $s.Port 180)) { throw "$($s.Name) не открыл порт $($s.Port), смотри $dir\log\java0.log" }
    "$($s.Name): PID $($p.Id), порт $($s.Port)"
}
