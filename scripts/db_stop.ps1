# Корректно останавливает MariaDB.

. "$PSScriptRoot\_common.ps1"

if (-not (Wait-Port 3306 1)) { 'MariaDB не запущена'; return }
& "$MariaBin\mariadb-admin.exe" "--defaults-extra-file=$ClientCnf" shutdown
if ($LASTEXITCODE -eq 0) { 'MariaDB остановлена' }
