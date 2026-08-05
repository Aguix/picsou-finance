# Local dev launcher.
# 1. Opens the Vite frontend in a NEW Windows Terminal tab.
# 2. Runs the backend (DB check + Spring Boot) in the CURRENT tab, via run-backend.ps1.
# Gitignored — local helper only.

$ErrorActionPreference = "Stop"

$frontendDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..\frontend")).Path

# --- 1. Launch the frontend in a new Windows Terminal tab ---------------------
$psExe = if (Get-Command pwsh -ErrorAction SilentlyContinue) { 'pwsh' } else { 'powershell' }
$wt = Get-Command wt.exe -ErrorAction SilentlyContinue
if ($wt) {
    # -w 0 targets the current terminal window; new-tab adds a tab (not a window).
    $wtArgs = @(
        '-w', '0',
        'new-tab',
        '--title', 'Picsou Frontend',
        '-d', $frontendDir,
        $psExe, '-NoExit', '-Command', 'bun run dev'
    )
    wt.exe @wtArgs
    Write-Host "Frontend starting in a new terminal tab." -ForegroundColor Cyan
} else {
    Write-Host "Windows Terminal (wt.exe) not found — opening the frontend in a new window instead." -ForegroundColor Yellow
    Start-Process $psExe -ArgumentList '-NoExit', '-Command', "Set-Location '$frontendDir'; bun run dev"
}

# --- 2. Run the backend in the current tab (blocking) ------------------------
# Delegates .env loading, the DB check, and the Spring Boot launch.
& (Join-Path $PSScriptRoot 'run-backend.ps1')
