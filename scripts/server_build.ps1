# Собирает сборку Mobius из исходников: накладывает патчи из patches\<Id>\ (уже наложенные
# пропускает), копирует модуль фантомов (phantoms\core и phantoms\<Id>) и накладывает его врезки
# (phantoms\<Id>\glue.patch), запускает Ant и обновляет jar-ы в servers\<Id>\server\libs, а также файлы данных
# (game/data, login/data) из патчей и модуля. Геодату развёрнутого сервера не трогает. Конфиги выкладывает
# python -m l2hub.cli deploy-configs (builder.deploy_configs): новые ключи и файлы — в сервер, правки пользователя
# остаются. Если сервер ещё не развёрнут — распаковывает сборку целиком.
# Пример: .\server_build.ps1 -Id ct26_highfive

param([Parameter(Mandatory = $true)][string]$Id)

. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'

$base = Join-Path $Root "servers\$Id"
$src = Get-ChildItem $base -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'build.xml') } | Select-Object -First 1
if (-not $src) { throw "Нет исходников с build.xml в $base" }
$relSrc = "servers/$Id/$($src.Name)"

# Модуль фантомов: phantoms\core — общий код и конфиги, phantoms\<Id> — данные хроники; оба повторяют дерево Mobius.
# Источник правды — phantoms\. Хэши скопированного запоминаются в servers\<Id>\.module_manifest.json: если файл
# в исходниках не совпадает с прошлой копией — его правили там, а не в модуле; такая версия перед перезаписью
# сохраняется в servers\<Id>\.module_overwritten\<время>\.
$phantomDir = Join-Path $Root "phantoms\$Id"
$moduleFiles = @()
if (Test-Path $phantomDir) {
    $stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
    $manifestPath = Join-Path $base '.module_manifest.json'
    $manifest = @{}
    if (Test-Path $manifestPath) {
        (Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $manifest[$_.Name] = $_.Value }
    }
    $newManifest = [ordered]@{}
    # В дерево идут только java\ и dist\; glue.patch, base.json и README в корне модуля — служебные.
    foreach ($from in @((Resolve-Path "$Root\phantoms\core").Path, (Resolve-Path $phantomDir).Path)) {
        $files = Get-ChildItem $from -Directory | Where-Object { $_.Name -in 'java', 'dist' } | Get-ChildItem -Recurse -File
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($from.Length + 1)
            $key = $rel -replace '\\', '/'
            $to = Join-Path $src.FullName $rel
            $hash = (Get-FileHash $f.FullName).Hash
            if (Test-Path $to) {
                $treeHash = (Get-FileHash $to).Hash
                if (($treeHash -ne $hash) -and $manifest.ContainsKey($key) -and ($treeHash -ne $manifest[$key])) {
                    $keep = Join-Path $base ".module_overwritten\$stamp\$rel"
                    New-Item -ItemType Directory -Force (Split-Path $keep) | Out-Null
                    Copy-Item $to $keep
                    "ВНИМАНИЕ: $key правили в исходниках, а не в phantoms\ — правка перезаписана модулем, копия: $keep"
                }
            }
            New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
            Copy-Item $f.FullName $to -Force
            $moduleFiles += $key
            $newManifest[$key] = $hash
        }
    }
    Write-Utf8NoBom $manifestPath @($newManifest | ConvertTo-Json)
    "Модуль фантомов в исходниках: файлов $($moduleFiles.Count)"
}

# Какие патчи уже наложены — проверка стопкой: во временную копию файлов, которые трогают патчи, патчи снимаются
# с конца (--reverse), поэтому каждый проверяется против состояния дерева сразу после себя. Так более поздний патч
# может менять файлы и строки более раннего (по одному ранний тогда перестал бы проходить проверку).
# Копия лежит рядом с исходниками (servers\ в .gitignore) и проверяется тем же git apply из корня репозитория,
# что и настоящее наложение: те же .gitattributes и обработка CRLF. Патч с LF-строками, наложенный на CRLF-файл,
# переписывает в LF весь файл, поэтому патчи к CRLF-файлам Mobius хранятся в CRLF.
function Get-AppliedPatches($patches, $srcDir) {
    # Несошедшаяся проверка — обычный ответ «не наложен». При Stop Windows PowerShell 5.1 превращает stderr git
    # в исключение даже с 2>$null, поэтому внутри функции — Continue (переменная локальна для функции).
    $ErrorActionPreference = 'Continue'
    $applied = @{}
    $tmpName = ".patchcheck_" + [guid]::NewGuid().ToString('N')
    $tmp = Join-Path $base $tmpName
    New-Item -ItemType Directory $tmp | Out-Null
    try {
        $files = $patches | ForEach-Object { Select-String -Path $_.FullName -Pattern '^(?:---|\+\+\+) [ab]/(\S+)' } |
            ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
        foreach ($rel in $files) {
            $from = Join-Path $srcDir $rel
            if (Test-Path $from -PathType Leaf) {
                $to = Join-Path $tmp $rel
                New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
                Copy-Item $from $to
            }
        }
        $relTmp = "servers/$Id/$tmpName"
        for ($i = $patches.Count - 1; $i -ge 0; $i--) {
            & git -C $Root apply --reverse --check "--directory=$relTmp" $patches[$i].FullName 2>$null
            if ($LASTEXITCODE -ne 0) { continue }
            & git -C $Root apply --reverse "--directory=$relTmp" $patches[$i].FullName 2>$null
            if ($LASTEXITCODE -eq 0) { $applied[$patches[$i].Name] = $true }
        }
    }
    finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    return $applied
}

# Патчи накладываются из корня репозитория с --directory: иначе git внутри репозитория
# молча пропустил бы пути вне текущей папки.
# Порядок: моды хроники из patches\<Id>\ по имени, затем врезки модуля фантомов.
$patchDir = Join-Path $Root "patches\$Id"
$patches = @()
if (Test-Path $patchDir) { $patches += @(Get-ChildItem $patchDir -Filter *.patch | Sort-Object Name) }
$glue = Join-Path $phantomDir 'glue.patch'
if (Test-Path $glue) { $patches += Get-Item $glue }
if ($patches.Count) {
    $applied = Get-AppliedPatches $patches $src.FullName
    foreach ($patch in $patches) {
        if ($applied[$patch.Name]) { "$($patch.Name): уже наложен"; continue }
        & git -C $Root apply "--directory=$relSrc" $patch.FullName
        if ($LASTEXITCODE -ne 0) { throw "Патч не наложился: $($patch.Name)" }
        "$($patch.Name): наложен"
    }
}

# Прошлый архив Ant перезапишет, а по нему выкладка конфигов отличает правки пользователя (пока нет записи
# servers\<Id>\.config_base) — копия рядом до конца скрипта.
$zip = Join-Path $base "build\$($src.Name).zip"
$prevZip = Join-Path $base "build\$($src.Name).prev.zip"
if (Test-Path $zip) { Copy-Item $zip $prevZip -Force }

function Invoke-DeployConfigs {
    $prevArgs = @()
    if (Test-Path $prevZip) { $prevArgs = @('--prev', $prevZip) }
    Push-Location (Join-Path $Root 'launcher')
    try { & uv run python -X utf8 -m l2hub.cli deploy-configs $Id @prevArgs }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "Конфиги не выложены (код $LASTEXITCODE)" }
    Remove-Item $prevZip -ErrorAction SilentlyContinue
}

$env:JAVA_HOME = $JavaHome
$log = Join-Path $base 'build.log'
$ErrorActionPreference = 'Continue'   # Ant пишет предупреждения в stderr
& "$Runtime\ant\bin\ant.bat" -f (Join-Path $src.FullName 'build.xml') 2>&1 | Out-File -FilePath $log -Encoding utf8
$antExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($antExit -ne 0) { throw "Сборка упала (код $antExit), смотри $log" }

$srv = Join-Path $base 'server'
if (Test-Path $srv) {
    & 7z e $zip 'libs/*.jar' "-o$srv\libs" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Не удалось обновить jar-ы из $zip" }
    "Сборка готова, jar-ы обновлены в $srv\libs"

    # Данные из патчей и модуля Ant кладёт только в архив сборки — копируем их в развёрнутый сервер.
    $touched = @($patches | ForEach-Object { Select-String -Path $_.FullName -Pattern '^\+\+\+ b/dist/(\S+)' } |
        ForEach-Object { $_.Matches[0].Groups[1].Value })
    $touched += @($moduleFiles | Where-Object { $_ -like 'dist/*' } | ForEach-Object { $_.Substring(5) })
    foreach ($rel in ($touched | Sort-Object -Unique)) {
        if ($rel -notmatch '^(game|login)/data/') { continue }
        $from = Join-Path $src.FullName "dist\$rel"
        $to = Join-Path $srv $rel
        if ((Test-Path $to) -and ((Get-FileHash $to).Hash -eq (Get-FileHash $from).Hash)) { continue }
        New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
        Copy-Item $from $to -Force
        "Данные обновлены: $rel"
    }
    Invoke-DeployConfigs
}
else {
    & 7z x $zip "-o$srv" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Не удалось распаковать $zip" }
    Invoke-DeployConfigs   # всё уже как в архиве — только запись о выкладке
    "Сборка готова и распакована в $srv; дальше — server_setup.ps1 -Id $Id"
}
