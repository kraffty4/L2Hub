# Общие пути и помощники для скриптов L2Hub. Подключается через точку: . "$PSScriptRoot\_common.ps1"

$Root      = Split-Path -Parent $PSScriptRoot
$Runtime   = Join-Path $Root 'runtime'
$JavaHome  = Join-Path $Runtime 'jdk-27'
$MariaBin  = Join-Path $Runtime 'mariadb\bin'
$DataDir   = Join-Path $Root 'data'
$DbDir     = Join-Path $DataDir 'mariadb'
$ClientCnf = Join-Path $DataDir 'client.cnf'   # логин и пароль root для клиентов MariaDB; не в git
$RunDir    = Join-Path $DataDir 'run'          # PID запущенных серверов

function Wait-Port([int]$Port, [int]$TimeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $client = New-Object Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1', $Port); return $true }
        catch { Start-Sleep -Milliseconds 500 }
        finally { $client.Dispose() }
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-DbPassword {
    $line = Get-Content $ClientCnf | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
    return $line.Substring('password='.Length)
}

function Stop-Gracefully([int]$ProcessId, [int]$TimeoutSec = 60) {
    # Ctrl+C в консоль процесса, ожидание выхода; если не вышел — жёсткое завершение.
    # Возвращает 'graceful', 'killed' или 'gone'.
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $p) { return 'gone' }
    $helper = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -Wait -PassThru -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSScriptRoot\_ctrlc.ps1`"", '-TargetPid', $ProcessId)
    if ($helper.ExitCode -eq 0 -and $p.WaitForExit($TimeoutSec * 1000)) { return 'graceful' }
    if (-not $p.HasExited) { Stop-Process -Id $ProcessId -Force }
    return 'killed'
}

function Write-Utf8NoBom([string]$Path, [string[]]$Lines) {
    [IO.File]::WriteAllLines($Path, $Lines, (New-Object Text.UTF8Encoding $false))
}
