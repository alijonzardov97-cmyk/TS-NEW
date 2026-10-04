# Включает собственный TURN-сервер (coturn) для звонков между разными сетями.
# Запуск (из папки проекта):  powershell -file scripts\enable-turn.ps1 -ip 203.0.113.5
# -ip: внешний (статический) IP вашего дома. Нужен проброс портов на ПК:
#   UDP и TCP 3478, UDP 49152-49200.
param([Parameter(Mandatory = $true)][string]$ip)

$ErrorActionPreference = 'Stop'
if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw 'нужен IPv4-адрес, например 203.0.113.5' }
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (-not (Test-Path $envFile)) { throw '.env не найден: сначала scripts\local-server.ps1' }

$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = [Convert]::ToBase64String($bytes).Replace('+', 'a').Replace('/', 'b').Replace('=', '')

$lines = Get-Content $envFile | Where-Object { $_ -notmatch '^(TURN_USER|TURN_PASSWORD|TURN_EXTERNAL_IP|ICE_SERVERS)=' }
$ice = '[{"urls":"turn:' + $ip + ':3478?transport=udp","username":"ts","credential":"' + $pass + '"},{"urls":"turn:' + $ip + ':3478?transport=tcp","username":"ts","credential":"' + $pass + '"}]'
$lines += "TURN_USER=ts"
$lines += "TURN_PASSWORD=$pass"
$lines += "TURN_EXTERNAL_IP=$ip"
$lines += "ICE_SERVERS=$ice"
Set-Content -Path $envFile -Value $lines -Encoding ascii
Write-Host 'Готово: .env обновлён. Теперь запустите:'
Write-Host '  docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile turn up -d --force-recreate'
