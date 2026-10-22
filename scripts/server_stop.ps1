# Корректно останавливает логин и гейм сборки: Ctrl+C в консоль процесса, гейм при этом
# через свой shutdown-хук сохраняет персонажей и отключает клиентов. Если процесс не вышел
# за отведённое время, он завершается жёстко, и скрипт об этом пишет.
# Пример: .\server_stop.ps1 -Id ct26_highfive

param([Parameter(Mandatory = $true)][string]$Id)

. "$PSScriptRoot\_common.ps1"

$runFile = Join-Path $RunDir "$Id.json"
if (-not (Test-Path $runFile)) { "Нет данных о запущенном $Id"; return }
$pids = Get-Content $runFile -Raw | ConvertFrom-Json

# Сначала гейм (сохраняет мир), потом логин.
foreach ($s in @(@{ Name = 'game'; Timeout = 120 }, @{ Name = 'login'; Timeout = 30 })) {
    $processId = $pids.($s.Name)
    if (-not $processId) { continue }
    $result = Stop-Gracefully $processId $s.Timeout
    switch ($result) {
        'graceful' { "$($s.Name) остановлен корректно (PID $processId)" }
        'killed'   { "ВНИМАНИЕ: $($s.Name) не вышел сам и завершён жёстко (PID $processId)" }
        'gone'     { "$($s.Name) уже не запущен (PID $processId)" }
    }
}
Remove-Item $runFile
