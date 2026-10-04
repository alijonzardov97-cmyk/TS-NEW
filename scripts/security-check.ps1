# Проверка защиты сервера TS. Ничего не меняет: только читает и печатает отчёт.
# Запуск (из папки проекта):  powershell -file scripts\security-check.ps1
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$script:bad = 0
$script:warn = 0

function Ok($t)   { Write-Host ("  [ОК]      " + $t) -ForegroundColor Green }
function Warn($t) { Write-Host ("  [ВНИМАНИЕ] " + $t) -ForegroundColor Yellow; $script:warn++ }
function Bad($t)  { Write-Host ("  [ОПАСНО]  " + $t) -ForegroundColor Red; $script:bad++ }

Write-Host ""
Write-Host "1. Порты контейнеров"
$ports = docker ps --format '{{.Names}}|{{.Ports}}' 2>$null
if (-not $ports) { Warn "docker не отвечает или контейнеры не запущены" }
else {
  foreach ($line in $ports) {
    $name, $p = $line.Split('|', 2)
    if ($name -eq 'ts-db') {
      if ($p -match '->') { Bad "база данных открыта наружу: $p" } else { Ok "база данных не публикует портов" }
    }
    if ($name -eq 'ts-server') {
      if ($p -match '0\.0\.0\.0|\[::\]') { Bad "сервер слушает все адреса: $p (должно быть 127.0.0.1:8080)" }
      elseif ($p -match '127\.0\.0\.1:8080') { Ok "сервер доступен только с этого компьютера (127.0.0.1:8080)" }
      else { Warn "порты сервера: $p" }
    }
  }
}

Write-Host ""
Write-Host "2. Усиление контейнера ts-server"
$ins = docker inspect ts-server --format '{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.CapDrop}}|{{.HostConfig.SecurityOpt}}' 2>$null
if ($ins) {
  $ro, $cap, $sec = $ins.Split('|')
  if ($ro -eq 'true') { Ok "файловая система только для чтения" } else { Bad "файловая система контейнера записываемая" }
  if ($cap -match 'ALL') { Ok "все привилегии Linux отключены" } else { Warn "привилегии контейнера не сброшены" }
  if ($sec -match 'no-new-privileges') { Ok "повышение привилегий запрещено" } else { Warn "no-new-privileges не включён" }
} else { Warn "не удалось прочитать настройки ts-server" }

Write-Host ""
Write-Host "3. Регистрация и настройки (.env)"
if (Test-Path .env) {
  $envText = Get-Content .env
  $reg = ($envText | Where-Object { $_ -match '^REGISTRATION_MODE=' } | Select-Object -First 1)
  if ($reg -match 'open') { Bad "регистрация открыта для всех (REGISTRATION_MODE=open)" }
  elseif ($reg -match 'closed') { Ok "регистрация закрыта" }
  else { Ok "регистрация только по приглашениям" }
  if ($envText -match '^SEARCH_ALL_USERS=true') { Warn "поиск находит всех пользователей сервера; для закрытой группы можно выключить (SEARCH_ALL_USERS=false)" }
  if ($envText -match '^TOTP_ENCRYPTION_KEY=\s*$') { Warn "TOTP_ENCRYPTION_KEY пустой" }
} else { Warn "файл .env не найден" }

Write-Host ""
Write-Host "4. Секреты не в git"
$tracked = git ls-files .env secrets 2>$null
if ($tracked) { Bad "в git попали секретные файлы: $($tracked -join ', ')" } else { Ok ".env и папка secrets не отслеживаются git" }

Write-Host ""
Write-Host "5. Tailscale"
$ts = Get-Command tailscale -ErrorAction SilentlyContinue
if ($ts) {
  $f = (tailscale funnel status 2>&1 | Out-String)
  if ($f -match 'Funnel on') { Bad "включён tailscale funnel: сервер виден всему интернету, выключите его" } else { Ok "funnel выключен (доступ только внутри вашей сети Tailscale)" }
} else { Warn "команда tailscale не найдена, проверьте funnel вручную" }

Write-Host ""
Write-Host "6. Шифрование диска Windows"
try {
  $bl = Get-BitLockerVolume -MountPoint $env:SystemDrive -ErrorAction Stop
  if ($bl.ProtectionStatus -eq 'On') { Ok "BitLocker включён на диске $env:SystemDrive" } else { Bad "BitLocker выключен: база и ключи лежат на диске в открытом виде" }
} catch { Warn "BitLocker не удалось проверить (нужен запуск от администратора). Проверьте: Параметры - Конфиденциальность и защита - Шифрование устройства" }

Write-Host ""
if ($script:bad -gt 0) { Write-Host ("Итог: опасных пунктов: " + $script:bad + ", предупреждений: " + $script:warn) -ForegroundColor Red }
elseif ($script:warn -gt 0) { Write-Host ("Итог: опасного нет, предупреждений: " + $script:warn) -ForegroundColor Yellow }
else { Write-Host "Итог: всё в порядке" -ForegroundColor Green }
