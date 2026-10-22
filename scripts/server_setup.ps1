# Настраивает сборку Mobius: создаёт базу и заливает SQL установщика (только если базы ещё нет),
# прописывает доступ к базе, включает локальный режим (логин и гейм слушают только 127.0.0.1,
# клиентам отдаётся только 127.0.0.1, внешний IP не запрашивается) и запуск без GUI.
# Повторный запуск безопасен: существующая база не трогается, конфиги перезаписываются.
# Пример: .\server_setup.ps1 -Id ct26_highfive

param([Parameter(Mandatory = $true)][string]$Id)

. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'

$srv = Join-Path $Root "servers\$Id\server"
if (-not (Test-Path $srv)) { throw "Нет распакованной сборки: $srv" }
if (-not (Wait-Port 3306 1)) { throw 'MariaDB не запущена: сначала db_start.ps1' }

$dbConfigs = @("$srv\login\config\Database.ini", "$srv\game\config\Database.ini")
$url = (Get-Content $dbConfigs[0] | Where-Object { $_ -match '^\s*URL\s*=' } | Select-Object -First 1)
$db = [regex]::Match($url, 'jdbc:mysql://[^/]+/([^?]+)').Groups[1].Value
if (-not $db) { throw "Не нашёл имя базы в $($dbConfigs[0])" }

$mysql = "$MariaBin\mariadb.exe"
$exists = & $mysql "--defaults-extra-file=$ClientCnf" -N -e "SHOW DATABASES LIKE '$db'"
if ($exists) {
    # Часть SQL установщика делает DROP TABLE, поэтому в существующую базу не заливаем.
    "База $db уже есть — заливку пропускаю"
}
else {
    & $mysql "--defaults-extra-file=$ClientCnf" -e "CREATE DATABASE ``$db`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
    if ($LASTEXITCODE -ne 0) { throw "Не удалось создать базу $db" }

    $count = 0
    foreach ($part in 'login', 'game') {
        foreach ($sql in Get-ChildItem "$srv\db_installer\sql\$part" -Filter *.sql | Sort-Object Name) {
            & $mysql "--defaults-extra-file=$ClientCnf" $db -e "source $($sql.FullName -replace '\\', '/')"
            if ($LASTEXITCODE -ne 0) { throw "Ошибка в $part\$($sql.Name)" }
            $count++
        }
    }
    "База ${db}: залито файлов — $count"
}

# Доступ к базе: 127.0.0.1 вместо localhost (MariaDB слушает только IPv4) и пароль из data\client.cnf.
$password = Get-DbPassword
foreach ($cfg in $dbConfigs) {
    $text = Get-Content $cfg
    $text = $text -replace '^(\s*URL\s*=\s*jdbc:mysql://)localhost/', '${1}127.0.0.1/'
    $text = $text -replace '^(\s*Password\s*=).*$', "`${1} $password"
    Write-Utf8NoBom $cfg $text
}

# Локальный режим и запуск без GUI.
$edits = @(
    @{ File = "$srv\login\config\Server.ini"; Key = 'LoginserverHostname'; Value = '127.0.0.1' },
    @{ File = "$srv\game\config\Server.ini"; Key = 'GameserverHostname'; Value = '127.0.0.1' },
    @{ File = "$srv\login\config\Interface.ini"; Key = 'EnableGUI'; Value = 'False' },
    @{ File = "$srv\game\config\Interface.ini"; Key = 'EnableGUI'; Value = 'False' }
)
foreach ($e in $edits) {
    $text = (Get-Content $e.File) -replace "^(\s*$($e.Key)\s*=).*$", "`${1} $($e.Value)"
    Write-Utf8NoBom $e.File $text
}

# Адреса для клиентов: при наличии ipconfig.xml сервер берёт их отсюда и не ходит
# за внешним IP на checkip.amazonaws.com.
Write-Utf8NoBom "$srv\game\config\ipconfig.xml" @(
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!-- L2Hub, локальный режим: клиентам отдаётся только 127.0.0.1. -->',
    '<gameserver address="127.0.0.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../data/xsd/ipconfig.xsd">',
    '	<define subnet="127.0.0.0/8" address="127.0.0.1" />',
    '</gameserver>'
)
"Сборка $Id настроена"
