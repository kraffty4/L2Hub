# Однократно: создаёт данные MariaDB в data\mariadb. Пароль root генерируется и пишется в data\client.cnf.
# MariaDB слушает только 127.0.0.1.

. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'

if (Test-Path $DbDir) { throw "Уже инициализировано: $DbDir" }
New-Item -ItemType Directory -Path $DataDir -Force | Out-Null

$chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

& "$MariaBin\mariadb-install-db.exe" "--datadir=$DbDir" "--password=$password" --port=3306
if ($LASTEXITCODE -ne 0) { throw "mariadb-install-db завершился с кодом $LASTEXITCODE" }

$ini = Join-Path $DbDir 'my.ini'
$lines = New-Object Collections.Generic.List[string]
foreach ($line in Get-Content $ini) {
    $lines.Add($line)
    if ($line.Trim() -eq '[mysqld]') {
        $lines.Add('bind-address=127.0.0.1')
        $lines.Add('character-set-server=utf8mb4')
        $lines.Add('collation-server=utf8mb4_unicode_ci')
    }
}
Write-Utf8NoBom $ini $lines

Write-Utf8NoBom $ClientCnf @('[client]', 'host=127.0.0.1', 'port=3306', 'user=root', "password=$password")
"Готово: $DbDir"
