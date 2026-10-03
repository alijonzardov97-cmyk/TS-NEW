# Adds Web Push (VAPID) keys to .env so the server can wake the phone through UnifiedPush
# when the app is closed. Run once from the project folder, then restart the server:
#   powershell -ExecutionPolicy Bypass -File scripts\enable-push.ps1
#   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml up -d --force-recreate ts
param([string]$AllowedHosts = "ntfy.sh")
$ErrorActionPreference = "Stop"

$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"
if (-not (Test-Path $envFile)) { throw ".env not found. Run scripts\local-server.ps1 first." }
if ((Get-Content $envFile -Raw) -match "(?m)^VAPID_PRIVATE_KEY=.+") {
    Write-Host "VAPID keys are already in .env - nothing to do."
    exit 0
}

function ConvertTo-B64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Fresh P-256 key pair: private = raw 32-byte scalar, public = uncompressed point (0x04 || X || Y).
$ec = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve]::CreateFromFriendlyName("nistP256"))
$p = $ec.ExportParameters($true)
$pub = [byte[]](@(4) + $p.Q.X + $p.Q.Y)

$lines = "`nVAPID_PRIVATE_KEY=" + (ConvertTo-B64Url $p.D) +
         "`nVAPID_PUBLIC_KEY=" + (ConvertTo-B64Url $pub) +
         "`nPUSH_ALLOWED_HOSTS=$AllowedHosts`n"
Add-Content -Path $envFile -Value $lines -Encoding ascii
Write-Host "Push keys added to .env (allowed push hosts: $AllowedHosts)."
Write-Host "Now restart the server and enable push in the app menu."
