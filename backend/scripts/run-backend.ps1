# Local dev launcher for the backend only.
# Loads the root .env, ensures the Postgres container is up, then runs Spring Boot
# in the `dev` profile. Gitignored — local helper only.
#
# Docker Compose loads .env for containers via `env_file:`, but `mvn` on the host
# does not — so we load it into the session here.

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$backendDir  = Join-Path $projectRoot "backend"
$envPath     = Join-Path $projectRoot ".env"

# --- 1. Load .env into the session --------------------------------------------
Get-Content $envPath | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}

# The datasource reads SPRING_DATASOURCE_PASSWORD; the .env only has
# POSTGRES_PASSWORD (Compose maps it for containers, we map it here).
$env:SPRING_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD

# --- 2. Ensure the database container is running ------------------------------
# `docker compose` is run from the project root so it auto-loads
# docker-compose.override.yml (which publishes 5432 for the host backend).
Push-Location $projectRoot
try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker does not appear to be running — start Docker Desktop first." -ForegroundColor Red
        exit 1
    }

    $running = @(docker compose ps --services --status running 2>$null)
    if ($running -contains 'db') {
        Write-Host "Database already running." -ForegroundColor Green
    } else {
        Write-Host "Starting database container..." -ForegroundColor Yellow
        # --wait blocks until the healthcheck passes, so the backend can connect.
        docker compose up -d --wait db
    }
} finally {
    Pop-Location
}

# --- 3. Run the backend (blocking) -------------------------------------------
# Spring Boot writes logs in UTF-8; switch the console to UTF-8 (code page 65001) so non-ASCII
# characters in log messages (em dashes, arrows) render correctly instead of mojibake (e.g. "ÔÇö").
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location $backendDir
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
