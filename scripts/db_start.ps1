# Поднимает MariaDB в фоне и ждёт порт 3306. Журнал ошибок — data\mariadb\*.err.

. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'

if (Wait-Port 3306 1) { 'MariaDB уже запущена'; return }
$p = Start-Process -FilePath "$MariaBin\mariadbd.exe" -ArgumentList "--defaults-file=`"$DbDir\my.ini`"" -WindowStyle Hidden -PassThru
if (-not (Wait-Port 3306 60)) { throw 'MariaDB не поднялась за 60 секунд, смотри data\mariadb\*.err' }
"MariaDB запущена, PID $($p.Id)"
